"""generate.py 的输入校验、命名推导与失败回滚；构建与运行验证见 scripts/verify_template.sh。"""

import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock

import generate


class GenerateTest(unittest.TestCase):
    """在临时仓库骨架中执行生成，不触碰真实仓库。"""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        services = self.root / 'platform' / 'services'
        services.mkdir(parents=True)
        (services / 'pom.xml').write_text('<project>\n    <modules>\n        <module>iam</module>\n    </modules>\n'
                                          '</project>\n', encoding='utf-8')
        deploy = self.root / 'deploy'
        (deploy / 'services').mkdir(parents=True)
        (deploy / 'nacos' / 'config').mkdir(parents=True)
        (deploy / 'rocketmq').mkdir(parents=True)
        (deploy / 'rocketmq' / 'topics.conf').write_text('# topics\n', encoding='utf-8')
        (deploy / 'powerjob').mkdir(parents=True)
        (deploy / 'powerjob' / 'apps.conf').write_text('iam\n', encoding='utf-8')
        (deploy / 'nacos' / 'config' / 'gateway.yaml').write_text(
            'gateway:\n  routing:\n    routes:\n      - id: iam\n        service: iam\n        paths: [/api/**]\n', encoding='utf-8')
        (deploy / 'compose.yml').write_text('services:\n  postgres:\n    image: postgres:18@sha256:abc\n',
                                            encoding='utf-8')

    def tearDown(self):
        shutil.rmtree(self.root)

    def test_bare_service_registers_module_and_has_no_example(self):
        result = generate.generate(self.root, 'order-service', 'com.company.order')
        output = self.root / 'platform' / 'services' / 'order-service'
        self.assertEqual('order', result['namespace'])
        self.assertTrue((output / 'src/main/java/com/company/order/bootstrap/OrderServiceApplication.java').is_file())
        self.assertFalse(any(output.rglob('OrderRepository.java')))
        self.assertIn('<module>order-service</module>', (output.parent / 'pom.xml').read_text(encoding='utf-8'))
        compose = (self.root / 'deploy/services/order-service.compose.yml').read_text(encoding='utf-8')
        self.assertIn('image: postgres:18@sha256:abc', compose)
        self.assertIn('ORDER_SERVICE_DB_USER: order_service', compose)
        self.assertNotIn('order-service', (self.root / 'deploy/nacos/config/gateway.yaml').read_text(encoding='utf-8'))
        seed = (self.root / 'deploy/nacos/config/order-service.yaml').read_text(encoding='utf-8')
        self.assertIn('source: nacos', seed)
        self.assertIn('SPRING_PROFILES_ACTIVE: config-center', compose)
        self.assertIn('ORDER_SERVICE_SCHEDULING_ENABLED: "true"', compose)
        self.assertIn('      - order-service-files:/var/lib/domain-forge/files\n', compose)
        self.assertIn('volumes:\n  order-service-files:\n', compose)
        self.assertFalse((output / 'src/main/resources/file-storage.yml').exists())
        pom = (output / 'pom.xml').read_text(encoding='utf-8')
        for optional in ('domain-forge-messaging', 'domain-forge-idempotency', 'domain-forge-file-storage'):
            self.assertNotIn(optional, pom)
        self.assertEqual('iam\norder-service\n',
                         (self.root / 'deploy/powerjob/apps.conf').read_text(encoding='utf-8'))
        for path in output.rglob('*'):
            if path.is_file():
                self.assertNotIn('@@', path.read_text(encoding='utf-8'), path)

    def test_example_uses_namespace_schema_and_environment_prefix(self):
        generate.generate(self.root, 'retail-order', 'com.company.retail', 'retail', with_example=True)
        output = self.root / 'platform' / 'services' / 'retail-order'
        permissions = json.loads((output / 'src/main/resources/iam/permissions.json').read_text(encoding='utf-8'))
        self.assertEqual('retail', permissions['namespace'])
        config = (output / 'src/main/resources/application.yml').read_text(encoding='utf-8')
        self.assertIn('${RETAIL_ORDER_DB_URL}', config)
        it = next(output.rglob('OrderServiceIT.java')).read_text(encoding='utf-8')
        self.assertIn('currentSchema=retail_order', it)
        self.assertIn('enabled: true', (output / 'src/main/resources/file-storage.yml').read_text(encoding='utf-8'))
        self.assertTrue((output / 'src/main/resources/db/migration/V3__order_file.sql').is_file())
        self.assertIn('${RETAIL_ORDER_FILES_ROOT:}', config)
        pom = (output / 'pom.xml').read_text(encoding='utf-8')
        for optional in ('domain-forge-messaging', 'domain-forge-idempotency', 'domain-forge-file-storage'):
            self.assertIn(optional, pom)
        self.assertFalse(any(output.rglob('*rchive*')))
        self.assertFalse((self.root / 'deploy/services/retail-order.archive.compose.yml').exists())
        routes = (self.root / 'deploy/nacos/config/gateway.yaml').read_text(encoding='utf-8')
        self.assertIn('      - id: retail-order\n        service: retail-order\n', routes)
        self.assertIn('      - id: iam\n', routes)
        self.assertNotIn('/internal/', routes)

    def test_call_example_targets_peer_and_appends_route(self):
        generate.generate(self.root, 'board-service', 'com.company.board', with_example=True, call_peer='order-service')
        output = self.root / 'platform' / 'services' / 'board-service'
        client = next(output.rglob('PeerOrderClient.java')).read_text(encoding='utf-8')
        self.assertIn('name = "order-service"', client)
        self.assertIn('${BOARD_SERVICE_PEER_URL:}', client)
        controller = next(output.rglob('OrderDigestController.java')).read_text(encoding='utf-8')
        self.assertIn('"/api/board/order-digest"', controller)
        routes = (self.root / 'deploy/nacos/config/gateway.yaml').read_text(encoding='utf-8')
        self.assertIn('      - id: board-service\n', routes)
        self.assertIn('      - id: board-service-calls\n        service: board-service\n        paths: [/api/board/order-digest]',
                      routes)
        topics = (self.root / 'deploy/rocketmq/topics.conf').read_text(encoding='utf-8')
        self.assertIn('topic board-service-orders NORMAL\n', topics)

    def test_consume_example_subscribes_to_source(self):
        generate.generate(self.root, 'board-service', 'com.company.board', call_peer='order-service',
                          source='order-service')
        output = self.root / 'platform' / 'services' / 'board-service'
        handler = next(output.rglob('SourceOrderSubmittedHandler.java')).read_text(encoding='utf-8')
        self.assertIn('return "order-service-orders";', handler)
        self.assertIn('return "board-service-order-tally";', handler)
        self.assertTrue(any(output.rglob('V10__order_tally.sql')))
        self.assertFalse(any(output.rglob('V2__outbox.sql')))
        pom = (output / 'pom.xml').read_text(encoding='utf-8')
        self.assertIn('domain-forge-messaging', pom)
        self.assertNotIn('domain-forge-idempotency', pom)
        topics = (self.root / 'deploy/rocketmq/topics.conf').read_text(encoding='utf-8')
        self.assertIn('group board-service-order-tally CONCURRENT\n', topics)
        routes = (self.root / 'deploy/nacos/config/gateway.yaml').read_text(encoding='utf-8')
        self.assertIn('paths: [/api/board/order-tally]', routes)

    def test_call_example_requires_valid_peer(self):
        for peer in ['Order', 'iam', 'gateway', 'board']:
            with self.assertRaises(ValueError):
                generate.generate(self.root, 'board', 'com.company.board', call_peer=peer)
        self.assertFalse((self.root / 'platform/services/board').exists())
        bare = self.root / 'platform' / 'services' / 'plain'
        generate.generate(self.root, 'plain', 'com.company.plain')
        self.assertFalse(any(bare.rglob('PeerOrder*.java')))
        self.assertFalse(any(bare.rglob('*Tally*.java')))
        self.assertFalse(any(bare.rglob('OrderSubmitted.java')))

    def test_failed_generation_restores_gateway_seed(self):
        seed = self.root / 'deploy/nacos/config/gateway.yaml'
        before = seed.read_text(encoding='utf-8')
        (self.root / 'platform/services/pom.xml').write_text('<project/>\n', encoding='utf-8')
        with self.assertRaises(ValueError):
            generate.generate(self.root, 'retail-order', 'com.company.retail', with_example=True,
                              call_peer='order-service')
        self.assertEqual(before, seed.read_text(encoding='utf-8'))

    def test_existing_deploy_file_is_not_overwritten(self):
        existing = self.root / 'deploy/services/order.compose.yml'
        existing.write_text('keep\n', encoding='utf-8')
        with self.assertRaises(ValueError):
            generate.generate(self.root, 'order', 'com.company.order')
        self.assertEqual('keep\n', existing.read_text(encoding='utf-8'))
        self.assertFalse((self.root / 'platform/services/order').exists())
        self.assertNotIn('<module>order</module>',
                         (self.root / 'platform/services/pom.xml').read_text(encoding='utf-8'))

    def test_rejects_invalid_or_existing_targets_without_changes(self):
        for name, package in [('Order', 'com.company.order'), ('iam', 'com.company.iam'), ('gateway', 'com.c.gw'),
                              ('order', 'com.company.class'), ('order', 'order')]:
            with self.assertRaises(ValueError):
                generate.generate(self.root, name, package)
        generate.generate(self.root, 'order', 'com.company.order')
        before = (self.root / 'platform' / 'services' / 'pom.xml').read_text(encoding='utf-8')
        with self.assertRaises(ValueError):
            generate.generate(self.root, 'order', 'com.company.order')
        self.assertEqual(before, (self.root / 'platform' / 'services' / 'pom.xml').read_text(encoding='utf-8'))

    def run_main(self, returncode, *extra):
        argv = ['generate.py', '--root', str(self.root), '--name', 'order-service', '--package', 'com.company.order']
        with mock.patch('sys.argv', argv + list(extra)), \
                mock.patch('generate.subprocess.run', return_value=mock.Mock(returncode=returncode)) as run:
            generate.main()
        return run

    def test_command_line_formats_generated_module(self):
        with mock.patch('builtins.print'):
            run = self.run_main(0)
        command = run.call_args.args[0]
        self.assertEqual(['-pl', 'services/order-service', 'spotless:apply'], command[-3:])

    def test_format_failure_keeps_service_and_exits_nonzero(self):
        with self.assertRaises(SystemExit) as exited, mock.patch('sys.stderr'):
            self.run_main(1)
        self.assertEqual(3, exited.exception.code)
        self.assertTrue((self.root / 'platform' / 'services' / 'order-service' / 'pom.xml').is_file())

    def test_no_format_skips_formatting(self):
        with mock.patch('builtins.print'):
            run = self.run_main(0, '--no-format')
        run.assert_not_called()

    def test_manifest_matches_assets(self):
        manifest = json.loads((generate.TEMPLATE / 'template.json').read_text(encoding='utf-8'))
        listed = {entry['source'] for entry in manifest['assets']}
        actual = {p.relative_to(generate.TEMPLATE).as_posix()
                  for p in (generate.TEMPLATE / 'assets').rglob('*') if p.is_file()}
        self.assertEqual(listed, actual)


if __name__ == '__main__':
    unittest.main()
