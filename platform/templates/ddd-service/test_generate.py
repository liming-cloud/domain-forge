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

    def tearDown(self):
        shutil.rmtree(self.root)

    def test_bare_service_registers_module_and_has_no_example(self):
        result = generate.generate(self.root, 'order-service', 'com.company.order')
        output = self.root / 'platform' / 'services' / 'order-service'
        self.assertEqual('order', result['namespace'])
        self.assertTrue((output / 'src/main/java/com/company/order/bootstrap/OrderServiceApplication.java').is_file())
        self.assertFalse(any(output.rglob('Note*.java')))
        self.assertIn('<module>order-service</module>', (output.parent / 'pom.xml').read_text(encoding='utf-8'))
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

    def test_rejects_invalid_or_existing_targets_without_changes(self):
        for name, package in [('Order', 'com.company.order'), ('iam', 'com.company.iam'),
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
