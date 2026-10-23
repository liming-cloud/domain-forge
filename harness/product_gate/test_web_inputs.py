"""R2 输入、消费边界与合同覆盖的定向反例；模拟输入不作为实际消费证据。"""

import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import web_verify
import xml.etree.ElementTree as ET

import environment
import build
import web_http
import web_build
import web_negative
import calibrate_http

ROOT = Path(__file__).resolve().parents[2]


class WebInputTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='domain-forge-web-input-')
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        self.project = self.directory / 'consumer'
        shutil.copytree(ROOT / 'platform/verification/consumers/consumer-a', self.project)

    def reject(self, code, action, *args):
        with self.assertRaises(environment.ProbeFailure) as failure:
            action(*args)
        self.assertEqual(code, failure.exception.diagnostic['code'])

    def test_http_preparation_uses_verified_product_maven(self):
        target = self.directory / 'http-tools'; target.mkdir()
        repository = self.directory / 'repository'; repository.mkdir()
        args = SimpleNamespace(prepare=str(target), repository=str(repository), http_tools=None)
        def resolved(command, **kwargs):
            project = Path(command[command.index('-f') + 1]).parent
            (project / 'dependencies').mkdir(); (project / 'dependencies/resolved.jar').write_bytes(b'unit fixture only')
            return SimpleNamespace(returncode=0)
        with patch.object(calibrate_http.subprocess, 'run', side_effect=resolved), patch.object(calibrate_http, 'libraries', return_value={'ok': True}) as observed:
            self.assertTrue(calibrate_http.prepare_with_home(args, Path('/verified-product-maven'))['ok'])
            observed.assert_called_once_with(args)

    def test_failed_consumer_preserves_native_evidence(self):
        workspace = self.directory / 'workspace'; output = workspace / 'evidence'; output.mkdir(parents=True)
        def fail(*args):
            logs = workspace / 'consumer-a-evidence'; logs.mkdir(); (logs / 'verify.log').write_text('native failure evidence')
            target = workspace / 'consumer-a/target'; target.mkdir(parents=True); (target / 'http-runtime.log').write_text('runtime diagnostics')
            preparation = workspace / 'http-preparation/tools'; preparation.mkdir(parents=True); (preparation / 'prepare.log').write_text('preparation diagnostics')
            raise environment.ProbeFailure('PG_NATIVE_FAILURE', 'injected native failure')
        args = SimpleNamespace(tools='fixed', http_tools='fixed', check='all')
        component = {'poms': {'domain-forge-web-support': {}, 'domain-forge-bom': {}}, 'artifact': {}}
        with patch.object(web_verify.foundation_build, 'shared_component', return_value={}), patch.object(web_build, 'isolated', return_value={}), patch.object(web_build, 'component', return_value=component), patch.object(web_build, 'publish', return_value={}), patch.object(web_build, 'tool_bytes', return_value={}), patch.object(web_build, 'consume', side_effect=fail):
            self.reject('PG_NATIVE_FAILURE', web_verify.inspect, ROOT, ROOT / 'platform/verification/smoke/boot-smoke', {}, [], {}, None, None, None, workspace, output, args)
        self.assertEqual((output / 'independent-builds/consumer-a-evidence/verify.log').read_text(), 'native failure evidence')
        self.assertTrue((output / 'independent-builds/consumer-a-runtime/http-runtime.log').is_file())
        self.assertTrue((output / 'independent-builds/http-preparation/tools/prepare.log').is_file())
        self.assertFalse(json.loads((output / 'web-report.json').read_text())['ok'])

    def test_frozen_contract_rejects_manual_edit_and_input_drift(self):
        root = self.directory / 'root'; root.mkdir()
        shutil.copytree(ROOT / 'platform', root / 'platform', ignore=shutil.ignore_patterns('target'))
        destination = root / environment.PRODUCT / environment.CONTRACTS
        (root / 'harness/product_gate').mkdir(parents=True)
        for name in ('a', 'b'):
            shutil.copyfile(ROOT / f'harness/product_gate/consumer-{name}-scenarios.json', root / f'harness/product_gate/consumer-{name}-scenarios.json')
        path = destination / 'consumer-a-openapi.json'; original = path.read_text(); generated = json.loads(original)
        self.assertEqual(web_http.contract_baseline(root, 'a', generated), path)
        changed = copy.deepcopy(generated); changed['paths'].pop('/unknown')
        self.reject('PG_HTTP_BASELINE', web_http.contract_baseline, root, 'a', changed)
        path.write_text(original + ' ')
        self.reject('PG_HTTP_BASELINE', web_http.contract_baseline, root, 'a', generated)
        path.write_text(original)
        scenario = root / 'harness/product_gate/consumer-a-scenarios.json'; scenario.write_text(scenario.read_text() + ' ')
        self.reject('PG_HTTP_BASELINE', web_http.contract_baseline, root, 'a', generated)
        shutil.copyfile(ROOT / 'harness/product_gate/consumer-a-scenarios.json', scenario)
        source = root / 'platform/shared/src/main/java/org/domainforge/shared/ApiResponse.java'
        source.write_text(source.read_text() + '// drift\n')
        self.reject('PG_HTTP_BASELINE', web_http.contract_baseline, root, 'a', generated)

    def test_component_fork_rejected_by_observation_entry(self):
        project = self.directory / 'web'; shutil.copytree(ROOT / 'platform/framework/domain-forge-web-support', project)
        pom = project / 'pom.xml'; pom.write_text(pom.read_text().replace('</project>', '<properties><maven.compiler.fork>true</maven.compiler.fork></properties></project>'))
        self.reject('SG_BUILD_CLASSPATH', web_build.observe, ROOT, project, None, None, False, [])

    def test_all_project_declarations_reject_floating_plugins(self):
        doc = ET.fromstring('<inventory><project path="/bom" version="1.0.0"><declaredPlugin group="org.apache.maven.plugins" artifact="maven-clean-plugin" version="3.4.1-SNAPSHOT"/></project></inventory>')
        self.reject('SG_BUILD_VERSION', build.select_inventory, doc, Path('/bom'))
        doc.find('project/declaredPlugin').set('version', '3.4.1')
        self.assertEqual(build.select_inventory(doc, Path('/bom')).find('project').get('version'), '1.0.0')

    def test_packaged_artifact_substitution_rejected_before_start(self):
        jar = self.directory / 'a.jar'; jar.write_bytes(b'B artifact')
        consumers = {name: {'observed': {'artifact': {'path': str(jar), 'sha256': '0' * 64}}} for name in ('a', 'b')}
        self.reject('PG_ARTIFACT_BINDING', web_http.packaged, ROOT, consumers, self.directory)

    def test_safe_message_is_independent_of_sentinel(self):
        _, cases = calibrate_http.scenarios(ROOT / 'harness/product_gate/consumer-a-scenarios.json')
        case = next(x for x in cases if x['request']['path'] == '/unknown')
        interaction = {'status': 500, 'body': {'code': 'INTERNAL_ERROR', 'message': 'java.lang.IllegalStateException at example.Controller:91', 'data': None}}
        self.assertTrue(web_http.safe_messages([interaction], [case]))

    def test_internal_diagnostic_rejects_missing_generic_duplicate_and_secret_logs(self):
        case = {'code': 'INTERNAL_ERROR', 'request': {'path': '/unknown'}}
        line = ' ERROR example platform_http_failure kind=unhandled cause=java.lang.IllegalStateException at=example.ContractController.unknown:147'
        self.assertEqual(web_http.failure_diagnostic(case, line)['count'], 1)
        for text in ('', ' ERROR internal error', line + '\n' + line, line.replace('unknown:147', 'unknown')):
            self.reject('PG_HTTP_DIAGNOSTIC', web_http.failure_diagnostic, case, text)
        self.reject('PG_HTTP_DIAGNOSTIC_SECRET', web_http.failure_diagnostic, case, line + ' synthetic-secret')

    def test_mapping_diagnostic_requires_the_actual_extension_or_validation_location(self):
        for mode, failure, location in [('throw', 'IllegalStateException', 'RejectionAdapter.map'),
                                        ('invalid', 'IllegalArgumentException', 'RejectionMappings.apply'),
                                        ('null', 'IllegalArgumentException', 'RejectionMappings.apply')]:
            case = {'code': 'INTERNAL_ERROR', 'request': {'path': '/mapping?mode=' + mode}}
            line = ' ERROR platform_http_failure kind=rejection-mapping cause=java.lang.' + failure + ' at=example.' + location + ':42'
            self.assertTrue(web_http.failure_diagnostic(case, line)['required'])
            self.reject('PG_HTTP_DIAGNOSTIC', web_http.failure_diagnostic, case, line.replace('rejection-mapping', 'unhandled'))

    def test_business_rejections_must_not_be_system_failures(self):
        case = {'code': 'DECLINED', 'request': {'path': '/rejected'}}
        self.assertFalse(web_http.failure_diagnostic(case, '')['required'])
        self.reject('PG_HTTP_DIAGNOSTIC', web_http.failure_diagnostic, case, ' ERROR platform_http_failure kind=unhandled')

    def test_bom_cannot_publish_direct_dependencies(self):
        pom = self.directory / 'bom.xml'
        pom.write_text('<project><groupId>org.domainforge</groupId><artifactId>domain-forge-bom</artifactId><version>0.1.0-r2.1</version><dependencyManagement><dependencies><dependency><groupId>org.domainforge</groupId><artifactId>domain-forge-web-support</artifactId><version>0.1.0-r2.1</version></dependency><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>4.0.8</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot</artifactId><version>4.0.8</version></dependency></dependencies></project>')
        self.reject('PG_CONSUMER_POM', web_build.consumer_pom, pom, 'domain-forge-bom', '0.1.0-r2.1', '4.0.8')

    def test_valid_no_parent_consumer_and_own_parent_consumer(self):
        web_build.consumer_model(ROOT, self.project, 'a')
        project = self.directory / 'consumer-b'
        shutil.copytree(ROOT / 'platform/verification/consumers/consumer-parent', project)
        web_build.consumer_model(ROOT, project, 'b')

    def test_missing_consumer_cannot_be_a_complete_result(self):
        self.reject('PG_CONSUMER_COVERAGE', web_negative.verify, ROOT, {'a': {}}, {}, None, None, self.directory)

    def test_reactor_parent_system_path_and_skip_bypasses(self):
        pom = self.project / 'pom.xml'; original = pom.read_text()
        changes = [('<modules><module>../../../framework/domain-forge-web-support</module></modules>', 'PG_CONSUMER_SCOPE'),
                   ('<parent><groupId>org.domainforge</groupId><artifactId>platform</artifactId><version>0.1.0</version></parent>', 'PG_CONSUMER_PARENT'),
                   ('<systemPath>/tmp/implicit.jar</systemPath>', 'PG_CONSUMER_SCOPE'),
                   ('<properties><skipTests>true</skipTests></properties>', 'PG_CONSUMER_TESTS')]
        for added, code in changes:
            with self.subTest(code=code):
                pom.write_text(original.replace('</project>', added + '</project>'))
                self.reject(code, web_build.consumer_model, ROOT, self.project, 'a')
        pom.write_text(original)
        web_build.consumer_model(ROOT, self.project, 'a')

    def test_platform_source_and_internal_import_rejected(self):
        source = self.project / 'src/main/java/Injected.java'
        for content in ['package org.domainforge.web; class Injected {}',
                        'import org.domainforge.web.internal.RejectionMappings; class Injected {}']:
            source.write_text(content)
            self.reject('PG_CONSUMER_INTERNAL', web_build.consumer_model, ROOT, self.project, 'a')
        source.unlink()
        web_build.consumer_model(ROOT, self.project, 'a')

    def test_seed_repository_excludes_all_platform_bytes(self):
        repository = self.directory / 'seed'
        for path in ['org/domainforge/component/1/component.jar', 'org/example/third/1/third.jar']:
            file = repository / path; file.parent.mkdir(parents=True); file.write_bytes(b'input')
        target = self.directory / 'repository'
        web_build.seed_repository(repository, target)
        self.assertFalse((target / 'org/domainforge').exists())
        self.assertTrue((target / 'org/example/third/1/third.jar').exists())

    def test_same_gav_cannot_be_overwritten(self):
        target = self.directory / 'published'
        version = web_build.lock(ROOT)['candidate_version']
        (target / 'org/domainforge/domain-forge-web-support' / version).mkdir(parents=True)
        result = {'domain-forge-web-support': {'pom': {'version': version}}}
        self.reject('PG_IMMUTABLE_GAV', web_build.publish, ROOT, ROOT / 'platform', result, [], {}, self.directory, target)

    def test_generated_pom_cannot_depend_on_internal_parent_or_variables(self):
        pom = self.directory / 'generated.xml'
        original = '<project><modelVersion>4.0.0</modelVersion><groupId>org.domainforge</groupId><artifactId>domain-forge-bom</artifactId><version>0.1.0-r2.1</version>{}</project>'
        for content in ['<parent/>', '<properties><version>${missing}</version></properties>', '<repositories/>']:
            pom.write_text(original.format(content))
            self.reject('PG_CONSUMER_POM', web_build.consumer_pom, pom, 'domain-forge-bom', '0.1.0-r2.1', '4.0.8')

    def test_http_omitted_route_and_semantic_drift_are_rejected(self):
        definition, cases = calibrate_http.scenarios(ROOT / 'harness/product_gate/consumer-a-scenarios.json')
        registered = [{'path': x['path'], 'method': x['method'], 'params': '[]', 'headers': '[]'} for x in definition['endpoints']]
        spec = {'paths': {x['path']: {x['method'].lower(): {}} for x in definition['endpoints']}}
        self.assertFalse(calibrate_http.coverage(registered, spec, definition))
        changed = copy.deepcopy(spec); changed['paths'].pop('/unknown')
        self.assertIn('SG_HTTP_GENERATION_COVERAGE', {x['code'] for x in calibrate_http.coverage(registered, changed, definition)})
        changed_definition = copy.deepcopy(definition); changed_definition['endpoints'].pop()
        self.assertIn('SG_HTTP_SCENARIO_COVERAGE', {x['code'] for x in calibrate_http.coverage(registered, spec, changed_definition)})
        refusal = next(x for x in cases if x['request']['path'] == '/rejected')
        errors = calibrate_http.semantics([{'status': 200, 'body': {'code': 'SUCCESS', 'message': '伪成功', 'data': None}}], [refusal])
        self.assertEqual(errors[0]['code'], 'SG_HTTP_SEMANTICS')


if __name__ == '__main__':
    unittest.main()
