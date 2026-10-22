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
    'access-contract': (environment.ACCESS_CONTRACT, 'access-contract-modules.json', False),
    'access-backend': (environment.ACCESS_BACKEND, 'access-backend-modules.json', False),
    'iam-client': (environment.IAM_CLIENT, 'iam-client-modules.json', False),
    'iam': (environment.IAM, 'iam-modules.json', True),
}
ARTIFACTS = {'access-contract': 'domain-forge-access-control-contract',
             'access-backend': 'domain-forge-access-control-backend',
             'iam-client': 'domain-forge-iam-client',
             'domain': 'domain-forge-domain', 'web-support': 'domain-forge-web-support', 'persistence': 'domain-forge-persistence'}


def lock(root):
    return json.loads((root / 'harness/product_gate/iam-baseline.json').read_text())


def module_contract(root, owner, declaration, observed, output):
    """从已审阅声明绑定本轮实际解析JAR；不从实现生成允许类型或调用位置。"""
    source = root / 'harness/product_gate' / declaration
    require(digest(source) == environment.baseline(root)['module_contracts'][declaration],
            'PG_COMPONENT_BINDING', '身份模块声明摘要不符')
    model = json.loads(source.read_text())
    for module in model['modules']:
        if module['id'] == owner:
            continue
        artifact = ARTIFACTS[module['id']]
        matches = {item['file']: item for compilation in observed['build']['compilations']
                   for item in compilation['dependencies'] if item['group'] == 'org.domainforge' and item['artifact'] == artifact}
        require(len(matches) == 1, 'PG_COMPONENT_BINDING', '缺少唯一同次组件制品：' + artifact)
        path, item = next(iter(matches.items()))
        require(Path(path).is_file(), 'PG_COMPONENT_BINDING', '组件边界必须绑定实际JAR')
        module['artifact'] = {'group': item['group'], 'artifact': artifact, 'version': item['version'], 'sha256': digest(Path(path))}
        module['depends_on'] = []
    target = output / declaration
    target.write_text(json.dumps(model, ensure_ascii=False, indent=2) + '\n')
    return target


def consumer_pom(root, project, name):
    """contract无运行依赖；backend的必要原生依赖正常传递，均不能携带内部parent。"""
    version = lock(root)['contract_version'] if name == 'domain-forge-access-control-contract' else lock(root)['version']
    path = project / 'target/.flattened-pom.xml'; model = ET.parse(path).getroot()
    require(all(model.find('{*}' + tag) is None for tag in
                ('parent', 'repositories', 'pluginRepositories', 'build', 'profiles', 'modules', 'distributionManagement'))
            and '${' not in path.read_text() and model.findtext('{*}groupId') == 'org.domainforge'
            and model.findtext('{*}artifactId') == name and model.findtext('{*}version') == version,
            'PG_CONSUMER_POM', '身份组件消费POM泄漏内部构建或坐标不符')
    dependencies = model.findall('./{*}dependencies/{*}dependency')
    require(all(calibrate_lifecycle.fixed_version(item.findtext('{*}version')) for item in dependencies),
            'PG_CONSUMER_POM', '身份组件存在浮动运行依赖')
    require(bool(dependencies) == (name in ('domain-forge-access-control-backend', 'domain-forge-iam-client')),
            'PG_CONSUMER_POM', '技术无关合同不能携带框架，backend不得遗漏运行依赖')
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
    """依赖组件的模块用本轮发布制品独立重建，保留专用仓库校验，不放宽reactor路径规则。"""
    import shutil
    import calibrate_maven
    destination = workspace / (owner + '-isolated') / 'platform'
    source = code / relative; project = destination / relative
    shutil.copytree(source, project, ignore=shutil.ignore_patterns('target'))
    parent = source.parent
    while parent.is_relative_to(code):
        target = destination / parent.relative_to(code) / 'pom.xml'
        target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(parent / 'pom.xml', target)
        if parent == code: break
        parent = parent.parent
    local = workspace / (owner + '-repository')
    web_build.seed_repository(repository, local)
    settings = output / (owner + '-settings.xml')
    url = Path(publication['repository']).as_uri()
    settings.write_text('<settings><mirrors><mirror><id>product-central</id><mirrorOf>external:*</mirrorOf>'
        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors><profiles><profile><id>iam-verification</id>'
        '<repositories><repository><id>iam-verification</id><url>' + url + '</url><releases><enabled>true</enabled>'
        '<checksumPolicy>fail</checksumPolicy></releases><snapshots><enabled>false</enabled></snapshots>'
        '</repository></repositories></profile></profiles><activeProfiles><activeProfile>iam-verification</activeProfile></activeProfiles></settings>')
    inventory = output / (owner + '-inventory.xml')
    observer_output = output / (owner + '-observer'); observer_output.mkdir()
    extension = calibrate_maven.observer(home, observer_output)
    argv = [home / 'bin/mvn', '-B', '-ntp', '-s', settings, '-gs', settings, '-Dmaven.repo.local=' + str(local)]
    execution = environment.command([*argv, '-Dmaven.ext.class.path=' + str(extension),
        '-Ddomainforge.scope.output=' + str(inventory), 'verify'], project, output / (owner + '-verify.log'), 600, env)
    observed = web_build.observe(root, project, ET.parse(inventory).getroot(), local, executable, lock(root)['required_tests'][owner])
    original = source / 'target' / Path(observed['artifact']['path']).name
    observed['reactor_comparison'] = compare_artifacts(original, Path(observed['artifact']['path']), executable)
    definition = module_contract(root, owner, declaration, observed, output)
    observed['quality'] = build.quality(root, project, observed, tools, definition, digest(definition))
    (output / (owner + '-quality.json')).write_text(json.dumps(observed['quality'], ensure_ascii=False, indent=2) + '\n')
    require(observed['quality']['ok'], 'PG_SOURCE_QUALITY', owner + ' 独立构建质量不符')
    return dict(observed, execution=execution, project=str(project), repository=str(local), argv=list(map(str, argv)))


def compare_artifacts(reactor, isolated, executable):
    """普通JAR逐字节一致；Boot仅允许其原生reactor分类产生的分层索引差异。"""
    with zipfile.ZipFile(reactor) as first, zipfile.ZipFile(isolated) as second:
        require(first.namelist() == second.namelist(), 'PG_ISOLATED_BYTES', '独立重建改变归档成员')
        differences = [name for name in first.namelist() if first.read(name) != second.read(name)]
        require(not differences or executable and differences == ['BOOT-INF/layers.idx'],
                'PG_ISOLATED_BYTES', '独立重建改变源码、资源或依赖字节')
    return {'reactor_sha256': digest(reactor), 'isolated_sha256': digest(isolated),
            'identical_bytes': digest(reactor) == digest(isolated), 'different_entries': differences,
            'basis': 'All archive members compared; Boot native layers classify current reactor modules separately',
            'reference': 'https://docs.spring.io/spring-boot/maven-plugin/packaging.html'}
