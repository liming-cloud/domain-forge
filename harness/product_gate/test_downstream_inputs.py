"""迁移后的制品与外部合同绑定反例；实际消费另由原生构建证明。"""

import copy
import json
from pathlib import Path
import tempfile
import unittest

import build
import environment
import foundation_build
import iam_build
import web_build

ROOT = Path(__file__).resolve().parents[2]


class DownstreamInputTests(unittest.TestCase):
    def test_current_layout_and_versions_are_the_only_supported_targets(self):
        self.assertEqual(set(iam_build.MODULES), {'access-control', 'iam-client', 'iam'})
        self.assertEqual(iam_build.MODULES['access-control'][0], environment.ACCESS)
        self.assertEqual(web_build.lock(ROOT)['candidate_version'], '0.3.0-iam.2')
        self.assertEqual(foundation_build.lock(ROOT)['version'], '0.3.0-iam.2')

    def test_shared_binding_rejects_wrong_identity_and_changed_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            jar = output / 'shared.jar'; jar.write_bytes(b'actual compilation classpath')
            item = {'group': 'org.domainforge', 'artifact': 'shared', 'version': '0.3.0-iam.2',
                    'extension': 'jar', 'scope': 'compile', 'file': str(jar)}
            observed = {'build': {'compilations': [{'purpose': 'production', 'dependencies': [item]}],
                                  'classpath_artifacts': {str(jar): environment.digest(jar)}}}
            def bind(value):
                return build.component_contract(ROOT, 'web-support', 'web-modules.json', value,
                                                output, {'shared': 'shared'}, '0.3.0-iam.2')
            definition = json.loads(bind(observed).read_text())
            shared = next(m for m in definition['modules'] if m['id'] == 'shared')
            self.assertEqual(shared['artifact']['sha256'], environment.digest(jar))
            for key, value in [('group', 'foreign'), ('artifact', 'shared-helper'), ('version', '9.0.0'),
                               ('extension', 'pom'), ('scope', 'test'), ('classifier', 'tests')]:
                changed = copy.deepcopy(observed)
                changed['build']['compilations'][0]['dependencies'][0][key] = value
                with self.subTest(key=key), self.assertRaises(environment.ProbeFailure) as failure:
                    bind(changed)
                self.assertEqual(failure.exception.diagnostic['code'], 'PG_COMPONENT_BINDING')
            jar.write_bytes(b'substituted after compilation')
            with self.assertRaises(environment.ProbeFailure) as failure:
                bind(observed)
            self.assertEqual(failure.exception.diagnostic['code'], 'PG_COMPONENT_BINDING')

    def test_web_pom_requires_shared_but_keeps_framework_dependencies_optional(self):
        with tempfile.TemporaryDirectory() as temporary:
            pom = Path(temporary) / 'pom.xml'
            prefix = '<project><groupId>org.domainforge</groupId><artifactId>domain-forge-web-support</artifactId><version>0.3.0-iam.2</version><dependencies>'
            shared = '<dependency><groupId>org.domainforge</groupId><artifactId>shared</artifactId><version>0.3.0-iam.2</version></dependency>'
            framework = '<dependency><groupId>org.springframework</groupId><artifactId>spring-web</artifactId><version>7.0.10</version><optional>true</optional></dependency>'
            def check(content):
                pom.write_text(prefix + content + '</dependencies></project>')
                return web_build.consumer_pom(pom, 'domain-forge-web-support', '0.3.0-iam.2', '4.0.8')
            check(shared + framework)
            for changed in (framework, shared + framework.replace('<optional>true</optional>', ''),
                            shared.replace('0.3.0-iam.2', '9.0.0') + framework):
                with self.subTest(content=changed), self.assertRaises(environment.ProbeFailure) as failure:
                    check(changed)
                self.assertEqual(failure.exception.diagnostic['code'], 'PG_CONSUMER_POM')


if __name__ == '__main__':
    unittest.main()
