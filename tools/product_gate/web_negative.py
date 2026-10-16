"""使用真实制品与新隔离副本验证缺失制品、错误版本及测试旁路。"""

import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

from environment import ProbeFailure, command, require
import web_build
import calibrate_maven


def resolution(root, name, consumer, publication, home, env, workspace):
    project = workspace / name
    shutil.copytree(consumer['project'], project, ignore=shutil.ignore_patterns('target'))
    output = workspace / (name + '-evidence'); output.mkdir()
    repository = workspace / (name + '-repository')
    web_build.seed_repository(Path(consumer['repository']), repository)
    remote = workspace / (name + '-remote')
    shutil.copytree(publication['repository'], remote)
    version = web_build.lock(root)['candidate_version']
    if name == 'missing-artifact':
        (remote / 'org/domainforge/domain-forge-web-support' / version / ('domain-forge-web-support-' + version + '.jar')).unlink()
        expected = 'domain-forge-web-support:jar:' + version
    else:
        pom = project / 'pom.xml'
        pom.write_text(pom.read_text().replace(version, '0.1.0-r2.missing'))
        expected = 'domain-forge-bom:pom:0.1.0-r2.missing'
    settings = output / 'settings.xml'
    settings.write_text(Path(consumer['argv'][4]).read_text().replace(publication['repository'], str(remote))
                        .replace('https://repo.maven.apache.org/maven2', remote.as_uri()))
    argv = [str(project / 'mvnw'), '-B', '-ntp', '-s', str(settings), '-gs', str(settings), '-Dmaven.repo.local=' + str(repository)]
    try:
        command([*argv, 'verify'], project, output / 'verify.log', 600, env)
    except ProbeFailure as failure:
        text = (output / 'verify.log').read_text()
        require('Could not find artifact' in text and expected in text, 'PG_NEGATIVE_CAUSE', '失败没有指向预期缺失制品：' + str(output))
        return {'name': name, 'expected_artifact': expected, 'actual_failure': failure.diagnostic, 'platform_cache_initially_absent': True}
    require(False, 'PG_NEGATIVE_MISSED', '缺失制品或错误版本仍成功')


def skipped_tests(root, consumer, home, env, workspace):
    project = workspace / 'skipped-tests'
    shutil.copytree(consumer['project'], project, ignore=shutil.ignore_patterns('target'))
    output = workspace / 'skipped-tests-evidence'; output.mkdir()
    extension = calibrate_maven.observer(home, output); inventory = output / 'inventory.xml'
    argv = [str(project / 'mvnw'), *consumer['argv'][1:]]
    command([*argv, '-DskipTests=true', '-Dmaven.ext.class.path=' + str(extension), '-Ddomainforge.scope.output=' + str(inventory), 'verify'],
            project, output / 'verify.log', 600, env)
    try:
        web_build.observe(root, project, ET.parse(inventory).getroot(), Path(consumer['repository']), True,
                          ['example.consumer.a.ClientResultTest#clientMustNotTreatHttp200BusinessRejectionAsSuccess'])
    except ProbeFailure as failure:
        require(failure.diagnostic['code'] == 'SG_BUILD_TESTS' and 'Tests are skipped' in (output / 'verify.log').read_text(),
                'PG_NEGATIVE_CAUSE', '跳过测试必须由实际 Surefire 覆盖检查拒绝')
        return failure.diagnostic
    require(False, 'PG_NEGATIVE_MISSED', '实际跳过测试未被拒绝')


def verify(root, consumers, publication, home, env, workspace):
    require(set(consumers) == {'a', 'b'}, 'PG_CONSUMER_COVERAGE', '反例仍要求两个消费者的真实正向证据')
    output = workspace / 'web-negative'; output.mkdir()
    result = {name: resolution(root, name, consumers['a'], publication, home, env, output) for name in ('missing-artifact', 'wrong-version')}
    result['skipped-tests'] = skipped_tests(root, consumers['a'], home, env, output)
    (output / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    return result
