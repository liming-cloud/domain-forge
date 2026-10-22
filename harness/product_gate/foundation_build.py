"""发布单库组件并独立消费模板，复用既有原生构建与源码检查。"""

import importlib.util
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
import zipfile

import build
import calibrate_maven
import environment
from environment import command, digest, require
import web_build

PACKAGE = 'example.generated.sample'


def lock(root):
    return json.loads((root / 'harness/product_gate/foundation-baseline.json').read_text())


def generator(code):
    """加载当前固定副本的标准库生成器；不加载未声明插件。"""
    spec = importlib.util.spec_from_file_location('foundation_template', code / environment.TEMPLATE / 'generate.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def component(root, code, observed, repository, tools, output):
    """检查普通组件的实际 JAR、合同与消费 POM。"""
    project = code / environment.PERSISTENCE
    result = web_build.observe(root, project, ET.parse(observed['inventory']['path']).getroot(),
                               repository, False, lock(root)['required_tests'])
    with zipfile.ZipFile(result['artifact']['path']) as archive:
        require(not any(name.startswith('BOOT-INF/') for name in archive.namelist()),
                'PG_LIBRARY_ARTIFACT', '持久化组件必须是普通 JAR')
    result['quality'] = build.quality(root, project, result, tools, 'persistence-modules.json')
    (output / 'persistence-quality.json').write_text(json.dumps(result['quality'], ensure_ascii=False, indent=2))
    require(result['quality']['ok'], 'PG_SOURCE_QUALITY', '持久化源码检查失败，见 persistence-quality.json')
    result['pom'] = web_build.consumer_pom(project / 'target/.flattened-pom.xml', 'domain-forge-persistence',
                                         lock(root)['version'], environment.baseline(root)['boot_version'])
    return result


def domain_component(root, code, observed, repository, tools, output):
    """领域标识是纯 Java 普通制品，独立消费不携带框架或内部 parent。"""
    project = code / environment.DOMAIN
    result = web_build.observe(root, project, ET.parse(observed['inventory']['path']).getroot(),
        repository, False, ['org.domainforge.domain.api.JavaOnlyConsumptionTest#compilesWithoutFrameworkOrSharedBaseClass'])
    result['quality'] = build.quality(root, project, result, tools, 'domain-modules.json')
    (output / 'domain-quality.json').write_text(json.dumps(result['quality'], ensure_ascii=False, indent=2))
    require(result['quality']['ok'], 'PG_SOURCE_QUALITY', '领域标识源码或精确结构不符')
    pom = project / 'target/.flattened-pom.xml'
    model = ET.parse(pom).getroot()
    require(model.find('{*}parent') is None and not model.findall('./{*}dependencies/{*}dependency')
            and model.findtext('{*}groupId') == 'org.domainforge'
            and model.findtext('{*}artifactId') == 'domain-forge-domain'
            and model.findtext('{*}version') == '0.3.0-iam.1',
            'PG_CONSUMER_POM', '领域标识消费 POM 必须无运行依赖')
    result['pom'] = {'path': str(pom), 'sha256': digest(pom)}
    return result


def publish(root, code, component, domain, publication, argv, env, output):
    """在既有 Web 独立消费结束后追加新坐标；绝不覆盖本轮已有 GAV。"""
    target = Path(publication['repository']); version = lock(root)['version']
    relative = 'org/domainforge/domain-forge-persistence/' + version + '/domain-forge-persistence-' + version
    require(not (target / relative).parent.exists(), 'PG_IMMUTABLE_GAV', '持久化候选坐标已经存在')
    tool = web_build.lock(root)['tools']['deploy']
    execution = command([*argv, '-N', '-f', code / 'pom.xml', ':'.join([tool['group'], tool['artifact'], tool['version'], 'deploy-file']),
                         '-Durl=' + target.as_uri(), '-DrepositoryId=foundation-verification',
                         '-Dfile=' + component['artifact']['path'], '-DpomFile=' + component['pom']['path'], '-DgeneratePom=false'],
                        code, output / 'persistence-publish.log', 600, env)
    artifacts = dict(publication['artifacts'])
    artifacts[relative + '.jar'] = component['artifact']['sha256']
    artifacts[relative + '.pom'] = component['pom']['sha256']
    relative_domain = 'org/domainforge/domain-forge-domain/0.3.0-iam.1/domain-forge-domain-0.3.0-iam.1'
    require(not (target / relative_domain).parent.exists(), 'PG_IMMUTABLE_GAV', '领域标识候选坐标已经存在')
    command([*argv, '-N', '-f', code / 'pom.xml', ':'.join([tool['group'], tool['artifact'], tool['version'], 'deploy-file']),
             '-Durl=' + target.as_uri(), '-DrepositoryId=foundation-verification',
             '-Dfile=' + domain['artifact']['path'], '-DpomFile=' + domain['pom']['path'], '-DgeneratePom=false'],
            code, output / 'domain-publish.log', 600, env)
    artifacts[relative_domain + '.jar'] = domain['artifact']['sha256']
    artifacts[relative_domain + '.pom'] = domain['pom']['sha256']
    require(all((target / path).is_file() and digest(target / path) == checksum for path, checksum in artifacts.items()),
            'PG_PUBLICATION', '本轮制品发布字节不符')
    require({p.name for p in (target / 'org/domainforge').iterdir()} ==
            {'domain-forge-web-support', 'domain-forge-bom', 'domain-forge-persistence', 'domain-forge-domain'},
            'PG_PUBLICATION', '制品仓库出现未批准的平台坐标')
    return {'repository': str(target), 'artifacts': artifacts, 'execution': execution}


def generate(code, workspace, name, own_parent=False):
    """真实生成两个独立工程；自有 parent 是有限夹具，不宣称任意 parent 兼容。"""
    app = workspace / name
    metadata = generator(code).generate(code / environment.TEMPLATE, app, 'example.generated', name, PACKAGE)
    if own_parent:
        parent = workspace / 'company-parent'; parent.mkdir()
        (parent / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
            '<groupId>example.company</groupId><artifactId>company-parent</artifactId><version>1.0.0</version><packaging>pom</packaging></project>')
        pom = app / 'pom.xml'
        pom.write_text(pom.read_text().replace('<modelVersion>4.0.0</modelVersion>', '<modelVersion>4.0.0</modelVersion>\n'
            '<parent><groupId>example.company</groupId><artifactId>company-parent</artifactId><version>1.0.0</version>'
            '<relativePath>../company-parent/pom.xml</relativePath></parent>'))
    if own_parent:
        metadata['company_parent_sha256'] = digest(parent / 'pom.xml')
    return app, metadata


def inject(code, app):
    """仅向验证副本加入显式列出的夹具；裸模板资产不变。"""
    generator_module = generator(code)
    values = {'PACKAGE': PACKAGE, 'PACKAGE_PATH': PACKAGE.replace('.', '/')}
    for origin in sorted((code / environment.FOUNDATION_FIXTURE / 'src').rglob('*')):
        require(not origin.is_symlink(), 'PG_INPUT', '验证夹具不可为符号链接')
        if origin.is_file():
            relative = origin.relative_to(code / environment.FOUNDATION_FIXTURE).as_posix()
            target = app / generator_module.render(relative.removesuffix('.tpl'), values)
            require(not target.exists(), 'PG_FIXTURE_SCOPE', '夹具不能覆盖裸模板源码')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(generator_module.render(origin.read_text(), values))
    # 夹具源码直接使用的原生 API 显式声明；产品模板没有这些验证职责。
    identities = [('com.baomidou', 'mybatis-plus-core', ''), ('com.baomidou', 'mybatis-plus-annotation', ''),
                  ('com.baomidou', 'mybatis-plus-extension', ''), ('org.mybatis', 'mybatis', '3.5.19'),
                  ('org.mybatis', 'mybatis-spring', '4.0.0'), ('org.springframework', 'spring-web', ''),
                  ('org.springframework', 'spring-tx', ''), ('org.springframework', 'spring-jdbc', '')]
    dependencies = ''.join('<dependency><groupId>' + group + '</groupId><artifactId>' + artifact + '</artifactId>' +
                   ('<version>' + version + '</version>' if version else '') + '</dependency>\n'
                   for group, artifact, version in identities)
    pom = app / 'pom.xml'; text = pom.read_text(); offset = text.index('\n  <dependencies>') + len('\n  <dependencies>')
    pom.write_text(text[:offset] + '\n' + dependencies + text[offset:])


def settings(path, publication):
    path.write_text('<settings><mirrors><mirror><id>product-central</id><mirrorOf>external:*</mirrorOf>'
        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors><profiles><profile><id>verification</id>'
        '<repositories><repository><id>foundation-verification</id><url>' + Path(publication['repository']).as_uri() +
        '</url><releases><enabled>true</enabled><checksumPolicy>fail</checksumPolicy></releases><snapshots><enabled>false</enabled></snapshots>'
        '</repository></repositories></profile></profiles><activeProfiles><activeProfile>verification</activeProfile></activeProfiles></settings>')


def required_tests(fixture):
    """固定测试身份由执行者声明，不从成功报告倒推覆盖。"""
    tests = [PACKAGE + '.bootstrap.LoggingStateTest#missingAsyncSinkIsVisibleWithoutBlocking']
    if fixture:
        tests += [PACKAGE + '.bootstrap.PersistenceRuntimeTest#' + method for method in
                  ('successfulPairCommits', 'checkedFailureRollsBackBothWrites', 'runtimeFailureRollsBackBothWrites')]
        tests += [PACKAGE + '.bootstrap.LoggingFailureTest#boundedLoggingCannotUndoCommittedWork',
                  PACKAGE + '.bootstrap.RuntimeLimitsTest#poolAcquisitionIsBounded',
                  PACKAGE + '.bootstrap.RuntimeLimitsTest#mapperStatementIsCancelledAtFiveSeconds',
                  PACKAGE + '.bootstrap.RuntimeLimitsTest#transactionDeadlineRollsBack']
    return tests


def consume(root, code, app, workspace, publication, repository, home, env, tools, fixture=False):
    """不 install 平台源码；从无平台坐标的缓存解析实际发布字节。"""
    output = workspace / (app.name + '-evidence'); output.mkdir()
    local = workspace / (app.name + '-repository'); seed = web_build.seed_repository(repository, local)
    environment.wrapper(root, app)
    config = output / 'settings.xml'; settings(config, publication)
    argv = [str(app / 'mvnw'), '-B', '-ntp', '-s', str(config), '-gs', str(config), '-Dmaven.repo.local=' + str(local)]
    wrapper = command([*argv, '--version'], app, output / 'wrapper.log', 300, env, preparation=True)
    require(str(home) in (output / 'wrapper.log').read_text(), 'PG_WRAPPER', '生成工程 Wrapper 使用了其他 Maven')
    extension = calibrate_maven.observer(home, output); inventory = output / 'inventory.xml'
    execution = command([*argv, '-Dmaven.ext.class.path=' + str(extension), '-Ddomainforge.scope.output=' + str(inventory), 'verify'],
                        app, output / 'verify.log', 600, env)
    require(not env.get('DB_PASSWORD') or env['DB_PASSWORD'] not in (output / 'verify.log').read_text(),
            'PG_DIAGNOSTIC_SECRET', '生成工程构建日志包含数据库秘密')
    document = ET.parse(inventory).getroot()
    require({node.get('path') for node in document.findall('project')} == {str(app)}, 'PG_CONSUMER_SCOPE', '生成工程借用了其他 reactor')
    tests = required_tests(fixture)
    observed = web_build.observe(root, app, document, local, True, tests)
    for path, checksum in publication['artifacts'].items():
        metadata = (local / path).parent / '_remote.repositories'
        require((local / path).is_file() and digest(local / path) == checksum and
                metadata.is_file() and Path(path).name + '>foundation-verification=' in metadata.read_text(),
                'PG_CONSUMER_ARTIFACT', '生成工程未从本轮仓库取回精确平台制品')
    require({p.name for p in (local / 'org/domainforge').iterdir() if p.is_dir()} ==
            {'domain-forge-web-support', 'domain-forge-bom', 'domain-forge-persistence', 'domain-forge-domain'},
            'PG_CONSUMER_PARENT', '生成工程消费了内部 parent 或未知平台制品')
    definition = module_contract(code, publication, output, fixture)
    quality = build.quality(root, app, observed, tools, definition, digest(definition))
    (output / 'quality.json').write_text(json.dumps(quality, ensure_ascii=False, indent=2))
    require(quality['ok'], 'PG_SOURCE_QUALITY', '生成工程源码检查失败：' + str(output / 'quality.json'))
    return {'project': str(app), 'output': str(output), 'repository': str(local), 'seed': seed,
            'wrapper': wrapper, 'execution': execution, 'observed': observed, 'quality': quality,
            'argv': argv, 'definition': str(definition)}


def module_contract(code, publication, output, fixture):
    """只渲染固定合同并绑定实际发布 JAR；不从实现倒推放行列表。"""
    source = code / environment.FOUNDATION_CONTRACT / ('fixture-modules.json' if fixture else 'bare-modules.json')
    declaration = json.loads(source.read_text())
    for module in declaration['modules']:
        if module['kind'] != 'component':
            continue
        require(module['id'] in {'web-support', 'persistence', 'domain'}, 'PG_COMPONENT_BINDING', '未知模板组件')
        name = 'domain-forge-' + module['id']
        matches = [(path, checksum) for path, checksum in publication['artifacts'].items()
                   if Path(path).name.startswith(name + '-') and path.endswith('.jar')]
        require(len(matches) == 1, 'PG_COMPONENT_BINDING', '组件合同缺少唯一发布制品')
        path, checksum = matches[0]
        module['artifact'] = {'group': 'org.domainforge', 'artifact': name, 'version': Path(path).parent.name, 'sha256': checksum}
    destination = output / 'modules.json'; destination.write_text(json.dumps(declaration, ensure_ascii=False, indent=2) + '\n')
    return destination
