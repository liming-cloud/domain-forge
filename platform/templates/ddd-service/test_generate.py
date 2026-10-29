"""generate.py 的输入校验、命名推导与失败回滚；构建与运行验证见 scripts/verify_template.sh。"""

import json
from pathlib import Path
import shutil
import tempfile
import unittest

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
        self.assertFalse(any(output.rglob('Note*.java')))
        self.assertIn('<module>order-service</module>', (output.parent / 'pom.xml').read_text(encoding='utf-8'))
        compose = (self.root / 'deploy/services/order-service.compose.yml').read_text(encoding='utf-8')
        self.assertIn('image: postgres:18@sha256:abc', compose)
        self.assertIn('ORDER_SERVICE_DB_USER: order_service', compose)
        self.assertNotIn('order-service', (self.root / 'deploy/nacos/config/gateway.yaml').read_text(encoding='utf-8'))
        seed = (self.root / 'deploy/nacos/config/order-service.yaml').read_text(encoding='utf-8')
        self.assertIn('source: nacos', seed)
        self.assertIn('SPRING_PROFILES_ACTIVE: config-center', compose)
        self.assertIn('ORDER_SERVICE_SCHEDULING_ENABLED: "true"', compose)
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
        it = next(output.rglob('NoteServiceIT.java')).read_text(encoding='utf-8')
        self.assertIn('currentSchema=retail_order', it)
        routes = (self.root / 'deploy/nacos/config/gateway.yaml').read_text(encoding='utf-8')
        self.assertIn('      - id: retail-order\n        service: retail-order\n', routes)
        self.assertIn('      - id: iam\n', routes)
        self.assertNotIn('/internal/', routes)

    def test_call_example_targets_peer_and_appends_route(self):
        generate.generate(self.root, 'board-service', 'com.company.board', with_example=True, call_peer='note-service')
        output = self.root / 'platform' / 'services' / 'board-service'
        client = next(output.rglob('PeerNoteClient.java')).read_text(encoding='utf-8')
        self.assertIn('name = "note-service"', client)
        self.assertIn('${BOARD_SERVICE_PEER_URL:}', client)
        controller = next(output.rglob('NoteDigestController.java')).read_text(encoding='utf-8')
        self.assertIn('"/api/board/note-digest"', controller)
        routes = (self.root / 'deploy/nacos/config/gateway.yaml').read_text(encoding='utf-8')
        self.assertIn('      - id: board-service\n', routes)
        self.assertIn('      - id: board-service-calls\n        service: board-service\n        paths: [/api/board/note-digest]',
                      routes)
        topics = (self.root / 'deploy/rocketmq/topics.conf').read_text(encoding='utf-8')
        self.assertIn('topic board-service-notes NORMAL\n', topics)

    def test_consume_example_subscribes_to_source(self):
        generate.generate(self.root, 'board-service', 'com.company.board', call_peer='note-service',
                          source='note-service')
        output = self.root / 'platform' / 'services' / 'board-service'
        handler = next(output.rglob('SourceNoteCreatedHandler.java')).read_text(encoding='utf-8')
        self.assertIn('return "note-service-notes";', handler)
        self.assertIn('return "board-service-note-tally";', handler)
        self.assertTrue(any(output.rglob('V10__note_tally.sql')))
        self.assertFalse(any(output.rglob('V2__outbox.sql')))
        topics = (self.root / 'deploy/rocketmq/topics.conf').read_text(encoding='utf-8')
        self.assertIn('group board-service-note-tally CONCURRENT\n', topics)
        routes = (self.root / 'deploy/nacos/config/gateway.yaml').read_text(encoding='utf-8')
        self.assertIn('paths: [/api/board/note-tally]', routes)

    def test_call_example_requires_valid_peer(self):
        for peer in ['Note', 'iam', 'gateway', 'board']:
            with self.assertRaises(ValueError):
                generate.generate(self.root, 'board', 'com.company.board', call_peer=peer)
        self.assertFalse((self.root / 'platform/services/board').exists())
        bare = self.root / 'platform' / 'services' / 'plain'
        generate.generate(self.root, 'plain', 'com.company.plain')
        self.assertFalse(any(bare.rglob('PeerNote*.java')))
        self.assertFalse(any(bare.rglob('*Tally*.java')))
        self.assertFalse(any(bare.rglob('NoteCreated.java')))

    def test_failed_generation_restores_gateway_seed(self):
        seed = self.root / 'deploy/nacos/config/gateway.yaml'
        before = seed.read_text(encoding='utf-8')
        (self.root / 'platform/services/pom.xml').write_text('<project/>\n', encoding='utf-8')
        with self.assertRaises(ValueError):
            generate.generate(self.root, 'retail-order', 'com.company.retail', with_example=True,
                              call_peer='note-service')
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

    def test_manifest_matches_assets(self):
        manifest = json.loads((generate.TEMPLATE / 'template.json').read_text(encoding='utf-8'))
        listed = {entry['source'] for entry in manifest['assets']}
        actual = {p.relative_to(generate.TEMPLATE).as_posix()
                  for p in (generate.TEMPLATE / 'assets').rglob('*') if p.is_file()}
        self.assertEqual(listed, actual)


if __name__ == '__main__':
    unittest.main()
