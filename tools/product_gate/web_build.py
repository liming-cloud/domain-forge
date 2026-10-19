"""R2 实际制品、发布 POM 与独立消费构建；不发布公共仓库。"""

import json
import os
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
import zipfile

import build
import environment
from environment import require, digest, command
import calibrate_lifecycle
import calibrate_maven
import calibrate_maven_source

WEB = Path('framework/domain-forge-web-support')
BOM = Path('domain-forge-bom')


def lock(root):
    return json.loads((root / 'tools/product_gate/web-baseline.json').read_text())


def consumer_pom(path, artifact, version, boot):
    """检查生成模型而非修订生成文件；BOM 保留 import，组件不携带宿主构建。"""
    model = ET.parse(path).getroot()
    require(all(model.find('{*}' + tag) is None for tag in
                ('parent', 'repositories', 'pluginRepositories', 'build', 'profiles', 'modules', 'distributionManagement')) and
            '${' not in path.read_text() and model.findtext('{*}groupId') == 'org.domainforge' and
            model.findtext('{*}artifactId') == artifact and model.findtext('{*}version') == version,
            'PG_CONSUMER_POM', '生成消费 POM 含内部构建、未解析属性或错误坐标')
    nodes = model.findall('./{*}dependencyManagement/{*}dependencies/{*}dependency')
    if artifact == 'domain-forge-bom':
        actual = {(x.findtext('{*}groupId'), x.findtext('{*}artifactId'), x.findtext('{*}version'),
                   x.findtext('{*}type', 'jar'), x.findtext('{*}scope', 'compile')) for x in nodes}
        require(model.find('{*}dependencies') is None and len(nodes) == 2 and actual == {('org.domainforge', 'domain-forge-web-support', version, 'jar', 'compile'),
                    ('org.springframework.boot', 'spring-boot-dependencies', boot, 'pom', 'import')},
                'PG_CONSUMER_POM', 'BOM 必须只管理真实组件并 import 固定 Boot')
    else:
        dependencies = model.findall('./{*}dependencies/{*}dependency')
        require(dependencies and all(x.findtext('{*}optional') == 'true' and
                calibrate_lifecycle.fixed_version(x.findtext('{*}version')) for x in dependencies),
                'PG_CONSUMER_POM', '组件依赖必须固定且由宿主选择框架，不强制容器')
    return {'path': str(path), 'sha256': digest(path)}


def observe(root, project, document, repository, executable, required_tests):
    """沿用 R1 原生观察检查，不以 Maven 成功替代测试、字节与依赖证据。"""
    build.compiler_model(ET.parse(project / "pom.xml").getroot(), project)
    document = build.select_inventory(document, project)
    inputs = calibrate_maven.project_inputs(project)
    compilations = calibrate_maven.compilation_inventory(document, project, inputs, repository)
    calibrate_lifecycle.compilation_target(compilations)
    dependencies, plugin_dependencies, constraints, plugin_constraints, resolved = calibrate_lifecycle.versions_and_artifacts(document, repository)
    tests = calibrate_lifecycle.test_results(project, compilations, set(required_tests))
    built = {'ok': True, 'inputs': inputs, 'compilations': compilations,
             'artifacts': {name: digest(Path(name)) for item in compilations for name in item['classes']},
             'classpath_artifacts': {name: digest(Path(name)) for item in compilations for name in item['classpath'] if Path(name).is_file()},
             'direct_dependencies': calibrate_maven.direct_dependencies(document)}
    artifact = build.packaged(document, project, built, dependencies) if executable else calibrate_lifecycle.packaged_artifact(document, project, compilations)
    calibrate_maven_source.unchanged_build(built, project)
    return {'build': built, 'tests': tests, 'artifact': artifact, 'dependencies': dependencies,
            'plugin_dependencies': plugin_dependencies, 'constraints': constraints, 'plugin_constraints': plugin_constraints,
            'resolved': resolved, 'effective_model': ET.tostring(document.find('project'), encoding='unicode')}


def component(root, code, observed, repository, tools, output):
    document = ET.parse(observed['inventory']['path']).getroot()
    project = code / WEB
    required = ['reservedCodesCannotBecomeBusinessRejections', 'duplicateAndBroadDeclarationsAreRejected',
                'exactMatchingDoesNotInspectSubclassOrCause', 'nonServletContextDoesNotActivateWebSupport']
    diagnostics = ['unknownFailureRetainsLocationAndCauseWithoutMessages', 'throwingMappingRetainsItsFailureLocation',
                   'invalidMappingRetainsValidationFailure', 'expectedRejectionAndMalformedInputAreNotSystemFailures',
                   'committedResponseRethrowsWithoutDuplicateDiagnostics', 'cyclicCausesAndLargeStacksRemainBounded']
    result = observe(root, project, document, repository, False,
                     ['org.domainforge.web.internal.ResponseContractTest#' + name for name in required] +
                     ['org.domainforge.web.internal.FailureDiagnosticsTest#' + name for name in diagnostics])
    require(not any(x.get('artifact') == 'spring-boot-maven-plugin' for x in document.findall('execution') if x.get('project') == str(project)),
            'PG_LIBRARY_ARTIFACT', '普通组件不得执行 Boot 打包')
    with zipfile.ZipFile(result['artifact']['path']) as jar:
        resources = {p.relative_to(project / 'src/main/resources').as_posix(): p for p in (project / 'src/main/resources').rglob('*') if p.is_file()}
        require(not any(n.startswith('BOOT-INF/') for n in jar.namelist()) and all(jar.read(n) == p.read_bytes() for n, p in resources.items()),
                'PG_LIBRARY_ARTIFACT', '普通组件含 Boot 内容或遗漏装配资源')
    result['quality'] = build.quality(root, project, result, tools, 'web-modules.json')
    (output / 'web-quality.json').write_text(json.dumps(result['quality'], ensure_ascii=False, indent=2))
    require(result['quality']['ok'], 'PG_SOURCE_QUALITY', '组件源码／架构检查失败，详见 web-quality.json')
    version = lock(root)['candidate_version']
    result['poms'] = {artifact: consumer_pom(code / module / 'target/.flattened-pom.xml', artifact, version, environment.baseline(root)['boot_version'])
                      for module, artifact in ((WEB, 'domain-forge-web-support'), (BOM, 'domain-forge-bom'))}
    return result


def tool_bytes(root, repository):
    result = {}
    for tool in lock(root)['tools'].values():
        for extension, expected in tool['files'].items():
            path = repository / tool['group'].replace('.', '/') / tool['artifact'] / tool['version'] / (tool['artifact'] + '-' + tool['version'] + '.' + extension)
            require(path.is_file() and digest(path) == expected['sha256'], 'PG_TOOL_VERSION', '新增工具实际字节偏离固定来源：' + str(path))
            result[str(path)] = digest(path)
    return result


def publish(root, code, result, argv, env, output, target):
    """只向本轮仓库外 file 仓库写入；已有 GAV 一律拒绝再次发布。"""
    version = lock(root)['candidate_version']
    require(not target.resolve().is_relative_to(root) and not target.is_symlink(), 'PG_PUBLICATION', '验证制品仓库必须是仓库外普通路径')
    tool = lock(root)['tools']['deploy']
    goal = ':'.join([tool['group'], tool['artifact'], tool['version'], 'deploy-file'])
    executions = []
    for artifact, pom in result['poms'].items():
        gav = target / 'org/domainforge' / artifact / version
        require(not gav.exists(), 'PG_IMMUTABLE_GAV', '禁止覆盖已有候选 GAV；变更必须选择新版本或新隔离验证仓库')
        file = result['artifact']['path'] if artifact == 'domain-forge-web-support' else pom['path']
        executions.append(command([*argv, '-N', '-f', str(code / 'pom.xml'), goal, '-Durl=' + target.as_uri(),
             '-DrepositoryId=r2-verification', '-Dfile=' + str(file), '-DpomFile=' + pom['path'], '-DgeneratePom=false'],
             code, output / (artifact + '-publish.log'), 600, env))
    expected = {'org/domainforge/' + artifact + '/' + version + '/' + artifact + '-' + version + '.pom': meta['sha256']
                for artifact, meta in result['poms'].items()}
    expected['org/domainforge/domain-forge-web-support/' + version + '/domain-forge-web-support-' + version + '.jar'] = result['artifact']['sha256']
    require(all((target / name).is_file() and digest(target / name) == checksum for name, checksum in expected.items()),
            'PG_PUBLICATION', '发布字节偏离本次生成产物')
    require({p.name for p in (target / 'org/domainforge').iterdir()} == {'domain-forge-web-support', 'domain-forge-bom'},
            'PG_PUBLICATION', '验证仓库不得发布内部 parent 或额外平台坐标')
    return {'repository': str(target), 'artifacts': expected, 'executions': executions}


def seed_repository(source, target):
    """只复用明确的第三方下载；平台文件与成功记录不能预种。"""
    def excluded(directory, names):
        return ['domainforge'] if Path(directory) == source / 'org' and 'domainforge' in names else []
    shutil.copytree(source, target, ignore=excluded)
    require(not (target / 'org/domainforge').exists(), 'PG_CONSUMER_CACHE', '独立消费者初始缓存包含平台制品')
    return {'source': str(source), 'target': str(target), 'platform_initially_absent': True}


def consumer_model(root, project, name):
    environment.wrapper(root, project)
    models = sorted(project.rglob('pom.xml'))
    models = [p for p in models if 'target' not in p.relative_to(project).parts]
    require(len(models) == (1 if name == 'a' else 2), 'PG_CONSUMER_SCOPE', '消费工程模型数量不符')
    for pom in models:
        doc = ET.parse(pom).getroot()
        require(not any(doc.find('.//{*}' + tag) is not None for tag in
                    ('systemPath', 'repositories', 'pluginRepositories', 'profiles', 'extensions')),
                'PG_CONSUMER_SCOPE', '消费工程含旁路源码、仓库或构建扩展')
        parent = doc.find('{*}parent')
        require(parent is None or name == 'b' and pom.parent == project / 'consumer-b' and
                    parent.findtext('{*}groupId') == 'example.consumer.b' and parent.findtext('{*}artifactId') == 'consumer-parent' and
                    parent.findtext('{*}relativePath') == '../pom.xml', 'PG_CONSUMER_PARENT', '消费者不能继承平台 parent 或外部源码')
        require(not any(x.text and ('skip' in x.tag.lower()) and x.text.strip().lower() == 'true' for x in doc.iter()),
                'PG_CONSUMER_TESTS', '消费者不得跳过测试')
        build.compiler_model(doc, pom.parent)
    require((ET.parse(project / 'pom.xml').findtext('./{*}parent') is None) and
            ([x.text for x in ET.parse(project / 'pom.xml').findall('./{*}modules/{*}module')] == ([] if name == 'a' else ['consumer-b'])),
            'PG_CONSUMER_SCOPE', '消费者聚合不能借用平台 reactor')
    for source in project.rglob('*.java'):
        text = source.read_text()
        require('package org.domainforge' not in text and 'org.domainforge.web.internal' not in text and
                'org.domainforge.web.autoconfigure' not in text, 'PG_CONSUMER_INTERNAL', '消费者不得复制平台源码或引用内部实现')


def consume(root, code, name, workspace, published, repository, cache, home, env, tools):
    project = workspace / ('consumer-' + name)
    shutil.copytree(code / environment.CONSUMERS[0 if name == 'a' else 1], project)
    consumer_model(root, project, name)
    output = workspace / ('consumer-' + name + '-evidence'); output.mkdir()
    local = workspace / ('consumer-' + name + '-repository')
    seed = seed_repository(repository, local)
    settings = output / 'settings.xml'
    settings.write_text('<settings><mirrors><mirror><id>product-central</id><mirrorOf>external:*</mirrorOf>'
        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors><profiles><profile><id>verification</id>'
        '<repositories><repository><id>r2-verification</id><url>' + Path(published['repository']).as_uri() +
        '</url><releases><enabled>true</enabled><checksumPolicy>fail</checksumPolicy></releases><snapshots><enabled>false</enabled></snapshots>'
        '</repository></repositories></profile></profiles><activeProfiles><activeProfile>verification</activeProfile></activeProfiles></settings>')
    argv = [str(project / 'mvnw'), '-B', '-ntp', '-s', str(settings), '-gs', str(settings), '-Dmaven.repo.local=' + str(local)]
    wrapper = command([*argv, '--version'], project, output / 'wrapper.log', 300, env, preparation=True)
    require(str(home) in (output / 'wrapper.log').read_text(), 'PG_WRAPPER', '消费者 Wrapper 没有使用固定 Maven')
    extension = calibrate_maven.observer(home, output); inventory = output / 'inventory.xml'
    execution = command([*argv, '-Dmaven.ext.class.path=' + str(extension), '-Ddomainforge.scope.output=' + str(inventory), 'verify'],
                        project, output / 'verify.log', 600, env)
    doc = ET.parse(inventory).getroot(); app = project if name == 'a' else project / 'consumer-b'
    require({x.get('path') for x in doc.findall('project')} == ({str(project)} if name == 'a' else {str(project), str(app)}),
            'PG_CONSUMER_SCOPE', '实际消费 reactor 与自有模型不符')
    required = ['example.consumer.' + name + '.ClientResultTest#clientMustNotTreatHttp200BusinessRejectionAsSuccess']
    if name == 'b':
        required.extend('example.consumer.btest.StartupContractTest#' + method for method in
                        ('defaultStartupIncludesActuatorWithoutAmbiguousMapping', 'narrowedOverrideDoesNotCompeteWithPlatformAdvice', 'duplicateMappingFailsActualStartup',
                         'broadMappingFailsActualStartup', 'competingAdviceFailsActualStartup'))
    observed = observe(root, app, doc, local, True, required)
    for path, checksum in published['artifacts'].items():
        require((Path(published['repository']) / path).is_file() and digest(Path(published['repository']) / path) == checksum,
                'PG_PUBLICATION', '消费期间验证仓库输入变化')
        metadata = (local / path).parent / '_remote.repositories'
        require(metadata.is_file() and (Path(path).name + '>r2-verification=') in metadata.read_text(),
                'PG_CONSUMER_CACHE', '平台制品缺少本轮显式文件仓库获取来源')
        require((local / path).is_file() and digest(local / path) == checksum, 'PG_CONSUMER_ARTIFACT', '实际取回制品散列不符')
    require({p.name for p in (local / 'org/domainforge').iterdir() if p.is_dir()} ==
            {'domain-forge-web-support', 'domain-forge-bom'}, 'PG_CONSUMER_PARENT', '消费路径仍依赖内部 parent 或额外平台制品')
    quality = build.quality(root, app, observed, tools, 'consumer-' + name + '-modules.json')
    (output / 'quality.json').write_text(json.dumps(quality, ensure_ascii=False, indent=2))
    public = set(json.loads((root / 'tools/product_gate/web-modules.json').read_text())['modules'][0]['contract_types'])
    violations = [x for x in quality['dependency_declarations'] if x['target'].startswith('org.domainforge.web.') and x['target'] not in public]
    require(not violations, 'PG_CONSUMER_INTERNAL', '消费方实际源码引用了平台内部类型')
    require(quality['ok'], 'PG_SOURCE_QUALITY', '消费工程源码／架构检查失败：' + str(output / 'quality.json'))
    return {'project': str(project), 'app': str(app), 'output': str(output), 'repository': str(local), 'seed': seed,
            'wrapper': wrapper, 'execution': execution, 'observed': observed, 'quality': quality, 'argv': argv}
