"""身份切片同次原生构建与固定公开合同；不把组件框架依赖强行标为可选。"""

import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

import build
import calibrate_lifecycle
import environment
from environment import digest, require
import web_build

MODULES = {
    'access-control': (environment.ACCESS, 'access-backend-modules.json', False),
    'iam-client': (environment.IAM_CLIENT, 'iam-client-modules.json', False),
    'iam': (environment.IAM, 'iam-modules.json', True),
}
ARTIFACTS = {'access-control': 'domain-forge-access-control',
             'iam-client': 'domain-forge-iam-client',
             'shared': 'shared', 'web-support': 'domain-forge-web-support', 'persistence': 'domain-forge-persistence'}


def lock(root):
    return json.loads((root / 'harness/product_gate/iam-baseline.json').read_text())


def module_contract(root, owner, declaration, observed, output):
    """从已审阅声明绑定本轮实际解析JAR；不从实现生成允许类型或调用位置。"""
    return build.component_contract(root, owner, declaration, observed, output, ARTIFACTS, lock(root)['version'])


def consumer_pom(root, project, name):
    """合并访问组件与调用组件正常传递必要依赖，均不能携带内部parent。"""
    version = lock(root)['version']
    path = project / 'target/.flattened-pom.xml'; model = ET.parse(path).getroot()
    require(all(model.find('{*}' + tag) is None for tag in
                ('parent', 'repositories', 'pluginRepositories', 'build', 'profiles', 'modules', 'distributionManagement'))
            and '${' not in path.read_text() and model.findtext('{*}groupId') == 'org.domainforge'
            and model.findtext('{*}artifactId') == name and model.findtext('{*}version') == version,
            'PG_CONSUMER_POM', '身份组件消费POM泄漏内部构建或坐标不符')
    dependencies = model.findall('./{*}dependencies/{*}dependency')
    require(all(calibrate_lifecycle.fixed_version(item.findtext('{*}version')) for item in dependencies),
            'PG_CONSUMER_POM', '身份组件存在浮动运行依赖')
    require(name in ('domain-forge-access-control', 'domain-forge-iam-client') and dependencies,
            'PG_CONSUMER_POM', '身份组件不得遗漏运行依赖或恢复旧拆分坐标')
    return {'path': str(path), 'sha256': digest(path), 'version': version}


def inspect(root, code, inventory, repository, tools, output):
    """每个新增真实模块均进入原生源码、依赖、DDD、API及查询路径检查。"""
    result = {}
    for owner, (relative, declaration, executable) in MODULES.items():
        project = code / relative
        observed = web_build.observe(root, project, inventory, repository, executable, lock(root)['required_tests'][owner])
        definition = module_contract(root, owner, declaration, observed, output)
        observed['quality'] = build.quality(root, project, observed, tools, definition, digest(definition))
        (output / (owner + '-quality.json')).write_text(json.dumps(observed['quality'], ensure_ascii=False, indent=2) + '\n')
        require(observed['quality']['ok'], 'PG_SOURCE_QUALITY', owner + ' 源码或架构不符，见质量报告')
        if not executable:
            observed['pom'] = consumer_pom(root, project, relative.name)
            with zipfile.ZipFile(observed['artifact']['path']) as jar:
                require(not any(name.startswith('BOOT-INF/') for name in jar.namelist()), 'PG_LIBRARY_ARTIFACT', '身份组件不能重打包')
        result[owner] = observed
    return result


def isolated(root, code, relative, owner, declaration, executable, publication, repository, home, env, tools, workspace, output):
    """复用隔离原生构建，再以身份模块的固定声明核查真实源码。"""
    observed = web_build.isolated(root, code, relative, owner, executable, publication, repository, home, env,
                                  workspace, output, lock(root)['required_tests'][owner])
    project = Path(observed['project'])
    definition = module_contract(root, owner, declaration, observed, output)
    observed['quality'] = build.quality(root, project, observed, tools, definition, digest(definition))
    (output / (owner + '-quality.json')).write_text(json.dumps(observed['quality'], ensure_ascii=False, indent=2) + '\n')
    require(observed['quality']['ok'], 'PG_SOURCE_QUALITY', owner + ' 独立构建质量不符')
    return observed
