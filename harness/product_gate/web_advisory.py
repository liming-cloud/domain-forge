"""扫描实际组件与独立消费者依赖；复用既有原生告警策略。"""

import json
from pathlib import Path
import shutil

import advisory
from environment import command, digest, require


def scan(project_root, relative_app, observed, argv, env, data, output):
    require(data and (Path(data) / 'odc.mv.db').is_file(), 'PG_ADVISORY_DATA', '缺少原生漏洞资料')
    source = Path(data) / 'odc.mv.db'
    require(not source.is_symlink(), 'PG_ADVISORY_DATA', '漏洞资料不能为链接')
    before = digest(source)
    isolated = output / 'scan-project'
    shutil.copytree(project_root, isolated, ignore=shutil.ignore_patterns('target'))
    app = isolated / relative_app
    database = output / 'scan-data'; database.mkdir()
    shutil.copy2(source, database / source.name)
    version = advisory.configure(app)
    invocation = [str(isolated / 'mvnw'), *argv[1:], '-f', str(app / 'pom.xml'), '-DdataDirectory=' + str(database),
                  '-DautoUpdate=false', '-DretireJsAnalyzerEnabled=false', 'org.owasp:dependency-check-maven:' + version + ':check']
    execution = command(invocation, app, output / 'advisory.log', 900, env)
    reports = {kind: app / ('target/dependency-check-report.' + kind) for kind in ('json', 'html')}
    require(all(p.is_file() and p.stat().st_size for p in reports.values()), 'PG_ADVISORY_REPORT', '原生扫描报告缺失')
    report = json.loads(reports['json'].read_text())
    require(report.get('scanInfo', {}).get('engineVersion') == version and report.get('dependencies'), 'PG_ADVISORY_REPORT', '扫描工具版本或实际范围无效')
    covered = advisory.coverage(report, observed)
    require(before == digest(source), 'PG_INPUT_CHANGED', '原始漏洞资料在运行中改变')
    return {'ok': True, 'execution': execution, 'data_sha256': before, 'covered_dependency_hashes': sorted(covered),
            'reports': {kind: {'path': str(path), 'sha256': digest(path)} for kind, path in reports.items()},
            'findings': [{'component': x.get('fileName'), 'vulnerabilities': x['vulnerabilities']}
                         for x in report['dependencies'] if x.get('vulnerabilities')]}
