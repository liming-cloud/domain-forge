"""模板输入和独立制品合同的可控反例；不以这些单测代替运行验证。"""

import copy
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

import build
import environment
import foundation_build
import web_build

ROOT = Path(__file__).resolve().parents[2]


class TemplateInputTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='domain-forge-template-input-')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name).resolve()
        self.source = self.workspace / 'template'
        shutil.copytree(ROOT / 'platform/templates/ddd-service', self.source)
        self.generator = foundation_build.generator(ROOT / 'platform')
        self.output = self.workspace / 'sample-service'

    def generate(self, **values):
        parameters = {'source': self.source, 'output': self.output, 'group': 'example.company',
                      'artifact': 'sample-service', 'package': 'example.company.sample'}
        parameters.update(values)
        return self.generator.generate(**parameters)

    def test_generation_is_independent_and_traceable(self):
        metadata = self.generate()
        self.assertEqual(metadata['template_version'], '0.3.0-iam.2')
        self.assertEqual(metadata, json.loads((self.output / 'template-origin.json').read_text()))
        self.assertIn('<artifactId>sample-service</artifactId>', (self.output / 'pom.xml').read_text())
        self.assertNotIn('<parent>', (self.output / 'pom.xml').read_text())
        self.assertTrue((self.output / 'mvnw').stat().st_mode & 0o100)
        for path in self.output.rglob('*'):
            if path.is_file():
                self.assertNotIn(b'@@', path.read_bytes(), str(path))
                self.assertNotIn('fixture', path.as_posix().lower())
        self.assertFalse(list(self.output.rglob('*.sql')))

    def test_current_contract_rejects_fixture_and_module_contract_drift(self):
        environment.foundation_sources(ROOT)
        root = self.workspace / 'snapshot'
        for directory in (environment.TEMPLATE, environment.FOUNDATION_FIXTURE, environment.FOUNDATION_CONTRACT):
            shutil.copytree(ROOT / environment.PRODUCT / directory, root / environment.PRODUCT / directory)
        environment.foundation_sources(root)
        fixture = root / environment.PRODUCT / environment.FOUNDATION_FIXTURE / 'migrations/V2__verification.sql'
        contract = root / environment.PRODUCT / environment.FOUNDATION_CONTRACT / 'fixture-modules.json'
        for path in (fixture, contract):
            original = path.read_bytes()
            path.write_bytes(original + b' ')
            with self.subTest(path=path.name), self.assertRaises(environment.ProbeFailure) as failure:
                environment.foundation_sources(root)
            self.assertEqual(failure.exception.diagnostic['code'], 'PG_TEMPLATE_INPUT')
            path.write_bytes(original)
        environment.foundation_sources(root)

    def test_component_binding_rejects_foreign_group_prefix_and_version_mismatch(self):
        artifacts = {
            'org/domainforge/' + name + '/0.3.0-iam.2/' + name + '-0.3.0-iam.2.jar': 'a' * 64
            for name in ('shared', 'domain-forge-web-support', 'domain-forge-persistence')}
        shared = 'org/domainforge/shared/0.3.0-iam.2/shared-0.3.0-iam.2.jar'
        for index, wrong in enumerate((
                'example/other/shared/0.3.0-iam.2/shared-0.3.0-iam.2.jar',
                'org/domainforge/shared-helper/0.3.0-iam.2/shared-helper-0.3.0-iam.2.jar',
                'org/domainforge/shared/0.3.0-iam.2/shared-9.0.0.jar')):
            output = self.workspace / ('binding-' + str(index)); output.mkdir()
            changed = dict(artifacts); changed[wrong] = changed.pop(shared)
            with self.subTest(path=wrong):
                with self.assertRaises(environment.ProbeFailure) as failure:
                    foundation_build.module_contract(ROOT / environment.PRODUCT, {'artifacts': changed}, output, False)
                self.assertEqual(failure.exception.diagnostic['code'], 'PG_COMPONENT_BINDING')
                self.assertFalse((output / 'modules.json').exists())

    def test_existing_output_keeps_original_bytes(self):
        self.output.mkdir(); sentinel = self.output / 'owned.txt'; sentinel.write_bytes(b'keep')
        with self.assertRaises(ValueError):
            self.generate()
        self.assertEqual(sentinel.read_bytes(), b'keep')
        self.assertEqual(list(self.output.iterdir()), [sentinel])

    def test_git_round_trip_preserves_official_wrapper_bytes(self):
        repository = self.workspace / 'repository'
        source = repository / 'platform/templates/ddd-service'
        shutil.copytree(self.source, source)
        shutil.copy2(ROOT / '.gitattributes', repository / '.gitattributes')
        checkout = self.workspace / 'checkout'
        checkout.mkdir()
        for arguments in (['init', '--quiet'], ['add', '--', '.'],
                          ['checkout-index', '--all', '--prefix=' + str(checkout) + '/']):
            subprocess.run(['git', '-c', 'core.autocrlf=input', '-c', 'core.attributesFile=/dev/null',
                            *arguments], cwd=repository, check=True, capture_output=True, timeout=30)
        self.generate(source=checkout / 'platform/templates/ddd-service')
        for name in ('mvnw', 'mvnw.cmd'):
            self.assertEqual((self.output / name).read_bytes(), (self.source / 'assets' / name).read_bytes())

    def test_invalid_gav_and_package_do_not_create_output(self):
        for values in ({'group':'x;echo'}, {'artifact':'../escape'}, {'package':'example.class.app'},
                       {'package':'single'}, {'artifact':'X'}, {'package':'a.b/../../x'}):
            with self.subTest(values=values), self.assertRaises(ValueError):
                self.generate(**values)
            self.assertFalse(self.output.exists())

    def test_output_parent_traversal_and_name_mismatch_are_rejected(self):
        for output in (self.workspace / 'other', self.workspace / 'nested/../sample-service'):
            with self.assertRaises(ValueError):
                self.generate(output=output)
        self.assertFalse(self.output.exists())

    def test_output_symlink_and_symlink_parent_are_rejected(self):
        target = self.workspace / 'target'; target.mkdir()
        self.output.symlink_to(target, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.generate()
        self.output.unlink()
        link = self.workspace / 'linked'; link.symlink_to(target, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.generate(output=link / 'sample-service')
        self.assertEqual(list(target.iterdir()), [])

    def test_manifest_and_asset_symlinks_are_rejected(self):
        for name in ('generate.py', 'template.json', 'assets/pom.xml.tpl'):
            with self.subTest(name=name):
                path = self.source / name; original = path.read_bytes(); saved = self.workspace / 'saved'
                saved.write_bytes(original); path.unlink(); path.symlink_to(saved)
                with self.assertRaises(ValueError):
                    self.generate()
                path.unlink(); path.write_bytes(original)
                self.assertFalse(self.output.exists())

    def test_undeclared_symlink_directory_is_rejected(self):
        (self.source / 'assets/link').symlink_to(self.workspace, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.generate()
        self.assertFalse(self.output.exists())

    def test_missing_changed_and_extra_assets_fail_before_output_creation(self):
        path = self.source / 'assets/pom.xml.tpl'; original = path.read_bytes()
        for mode in ('missing', 'changed', 'extra'):
            path.write_bytes(original)
            extra = self.source / 'assets/unknown.txt'; extra.unlink(missing_ok=True)
            if mode == 'missing': path.unlink()
            if mode == 'changed': path.write_bytes(original + b' ')
            if mode == 'extra': extra.write_bytes(b'unknown')
            with self.subTest(mode=mode), self.assertRaises((ValueError, OSError)):
                self.generate()
            self.assertFalse(self.output.exists())

    def test_manifest_target_escape_and_duplicate_are_rejected(self):
        path = self.source / 'template.json'; original = json.loads(path.read_text())
        for target in ('../outside', '/outside', original['assets'][1]['target']):
            mutated = copy.deepcopy(original); mutated['assets'][0]['target'] = target
            path.write_text(json.dumps(mutated))
            with self.assertRaises(ValueError): self.generate()
            self.assertFalse(self.output.exists())

    def test_new_bom_retains_web_and_rejects_missing_persistence(self):
        baseline = foundation_build.lock(ROOT)
        header = '<project><modelVersion>4.0.0</modelVersion><groupId>org.domainforge</groupId><artifactId>domain-forge-bom</artifactId><version>' + baseline['version'] + '</version><dependencyManagement><dependencies>'
        dependencies = ['<dependency><groupId>'+g+'</groupId><artifactId>'+a+'</artifactId><version>'+v+'</version><type>'+t+'</type><scope>'+s+'</scope></dependency>' for g,a,v,t,s in baseline['managed_dependencies']]
        path = self.workspace / 'bom.xml'
        path.write_text(header + ''.join(dependencies) + '</dependencies></dependencyManagement></project>')
        web_build.consumer_pom(path, 'domain-forge-bom', baseline['version'], '4.0.8', baseline['managed_dependencies'])
        omitted = ''.join(item for item in dependencies if '<artifactId>domain-forge-persistence</artifactId>' not in item)
        changed = ''.join(dependencies).replace('<version>' + baseline['version'] + '</version>', '<version>9.0.0</version>')
        for content in (omitted, changed):
            path.write_text(header + content + '</dependencies></dependencyManagement></project>')
            with self.assertRaises(environment.ProbeFailure) as failure:
                web_build.consumer_pom(path, 'domain-forge-bom', baseline['version'], '4.0.8', baseline['managed_dependencies'])
            self.assertEqual(failure.exception.diagnostic['code'], 'PG_CONSUMER_POM')

    def test_framework_boms_reject_mixed_versions_missing_and_extra_imports(self):
        original = ET.parse(ROOT / 'platform/pom.xml').getroot()
        build.parent_management(ROOT, original)
        for index in range(3):
            changed = copy.deepcopy(original)
            changed.findall('./{*}dependencyManagement/{*}dependencies/{*}dependency')[index].find('{*}version').text = '0.0.1'
            with self.subTest(mutation='mixed-version', index=index):
                with self.assertRaises(environment.ProbeFailure):
                    build.parent_management(ROOT, changed)
        for mutation in ('missing', 'extra', 'reordered'):
            changed = copy.deepcopy(original)
            imports = changed.find('./{*}dependencyManagement/{*}dependencies')
            if mutation == 'missing':
                imports.remove(imports[-1])
            elif mutation == 'extra':
                imports.append(copy.deepcopy(imports[-1]))
            else:
                last = imports[-1]
                imports.remove(last)
                imports.insert(0, last)
            with self.subTest(mutation=mutation):
                with self.assertRaises(environment.ProbeFailure):
                    build.parent_management(ROOT, changed)


if __name__ == '__main__':
    unittest.main()
