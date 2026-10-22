"""阶段三本地产品适配；保持既有检查，性能另用声明资源的显式本地入口。"""

import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

import build
import environment
from environment import command, digest, require
import iam_build
import iam_integration
import web_advisory
import web_build


def inspect(root, project, observed, foundation, argv, env, home, repository, workspace, output, args):
    code = environment.product_root(project)
    directory = output / 'iam'
    directory.mkdir()
    result = {'ok': False, 'human_acceptance': False, 'remote_delivery_proven': False,
              'performance': 'separate fixed-resource local verification; not inferred from CI'}
    try:
        owner = 'access-contract'
        relative, declaration, _ = iam_build.MODULES[owner]
        contract = web_build.observe(root, code / relative, ET.parse(observed['inventory']['path']).getroot(),
                                     repository, False, iam_build.lock(root)['required_tests'][owner])
        definition = iam_build.module_contract(root, owner, declaration, contract, directory)
        contract['quality'] = build.quality(root, code / relative, contract, args.tools, definition, digest(definition))
        require(contract['quality']['ok'], 'PG_SOURCE_QUALITY', '身份公开合同质量失败')
        result[owner] = contract
        publication = dict(foundation['publication'])
        artifacts = dict(publication['artifacts'])
        target = Path(publication['repository'])
        for owner in ['access-contract', 'access-backend', 'iam-client']:
            relative, _, _ = iam_build.MODULES[owner]
            name = relative.name
            pom = iam_build.consumer_pom(root, code / relative, name)
            version = pom['version']
            jar = code / relative / 'target' / (name + '-' + version + '.jar')
            prefix = f'org/domainforge/{name}/{version}/{name}-{version}'
            require(not (target / prefix).parent.exists(), 'PG_IMMUTABLE_GAV', '身份组件不能覆盖本轮已有坐标')
            command([*argv, '-N', '-f', code / 'pom.xml',
                'org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file',
                '-Durl=' + target.as_uri(), '-DrepositoryId=iam-verification',
                '-Dfile=' + str(jar), '-DpomFile=' + pom['path'], '-DgeneratePom=false'],
                code, directory / (owner + '-publish.log'), 600, env)
            artifacts[prefix + '.jar'] = digest(jar)
            artifacts[prefix + '.pom'] = pom['sha256']
        require(all(digest(target / name) == checksum for name, checksum in artifacts.items()),
                'PG_PUBLICATION', '身份组件发布字节不符')
        publication['artifacts'] = artifacts
        result['publication'] = publication
        publication_file = directory / 'publication.json'
        publication_file.write_text(json.dumps(publication, indent=2) + '\n')
        for owner in ['access-backend', 'iam-client', 'iam']:
            relative, declaration, executable = iam_build.MODULES[owner]
            result[owner] = iam_build.isolated(root, code, relative, owner, declaration, executable,
                publication, repository, home, env, args.tools, workspace, directory)
        result['integration'] = iam_integration.inspect(code, argv, env, directory)
        command([sys.executable, '-B', code / 'verification/iam/consumer.py', '--code', code,
                 '--publication', publication_file, '--repository', repository,
                 '--maven', home / 'bin/mvn', '--output', directory], code, directory / 'consumer.log', 900, env)
        result['consumer'] = json.loads((directory / 'consumer-build.json').read_text())
        require(result['consumer']['ok'], 'PG_IAM_CONSUMER', '独立身份消费失败')
        if args.check in ('all', 'behavior-scenarios', 'runtime-scenarios', 'product-scenarios'):
            # 已批准恢复窗口本身600秒；预算包含实际启动与故障恢复，不缩短产品窗口。
            command([sys.executable, '-B', code / 'verification/iam/scenarios.py', '--code', code,
                     '--consumer', result['consumer']['artifact'], '--output', directory],
                    code, directory / 'runtime.log', 900, env)
            result['runtime'] = json.loads((directory / 'iam-runtime.json').read_text())
            require(result['runtime']['ok'], 'PG_IAM_RUNTIME', 'IAM运行验证失败')
        if args.check in ('all', 'dependency-risk-gate'):
            result['advisory'] = {}
            for owner in ['access-backend', 'iam-client', 'iam']:
                module = result[owner]
                scan = directory / (owner + '-advisory')
                scan.mkdir()
                isolated_code = Path(module['project'])
                while isolated_code.name != 'platform':
                    isolated_code = isolated_code.parent
                # 漏洞检查沿用官方Wrapper，独立解析的本轮依赖清单必须完整覆盖。
                import shutil
                shutil.copy2(code / 'mvnw', isolated_code / 'mvnw')
                shutil.copytree(code / '.mvn', isolated_code / '.mvn')
                result['advisory'][owner] = web_advisory.scan(isolated_code,
                    Path(module['project']).relative_to(isolated_code), module, module['argv'],
                    env, args.advisory_data, scan)
        result['ok'] = True
        return result
    finally:
        (directory / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
