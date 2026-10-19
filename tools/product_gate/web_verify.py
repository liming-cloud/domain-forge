"""R2 公共产品入口的组件、独立消费、合同及告警适配。"""

import json
import os
from pathlib import Path
import tempfile
import shutil
from types import SimpleNamespace

from environment import require, product_root
import calibrate_http
import web_build
import web_http
import web_advisory
import web_negative


def inspect(root, project, observed, argv, env, home, repository, cache, workspace, output, args):
    result = {'ok': False, 'human_acceptance': False, 'remote_delivery_proven': False}
    try:
        return inspect_product(root, project, observed, argv, env, home, repository, cache, workspace, output, args, result)
    finally:
        preserve_evidence(workspace, output)
        (output / 'web-report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


def preserve_evidence(workspace, output):
    """成功与失败都保留原生证据；缓存和可执行制品不混入 CI 证据包。"""
    evidence = output / 'independent-builds'; evidence.mkdir(exist_ok=True)
    directories = [*workspace.glob('consumer-*-evidence'), *workspace.glob('web-negative/*-evidence')]
    for directory in directories:
        shutil.copytree(directory, evidence / directory.name, dirs_exist_ok=True)
    for name in ('a', 'b'):
        app = workspace / ('consumer-' + name) / ('app' if name == 'b' else '.')
        for path in (app / 'target').rglob('*'):
            if path.is_file() and (path.name.startswith('http-') or 'surefire-reports' in path.parts) and path.suffix in ('.log', '.xml', '.txt'):
                destination = evidence / ('consumer-' + name + '-runtime') / path.relative_to(app / 'target')
                destination.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(path, destination)
    for log in (workspace / 'http-preparation').rglob('prepare.log'):
        destination = evidence / 'http-preparation' / log.relative_to(workspace / 'http-preparation')
        destination.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(log, destination)
    report = workspace / 'web-negative/report.json'
    if report.is_file():
        shutil.copy2(report, evidence / 'web-negative.json')


def inspect_product(root, project, observed, argv, env, home, repository, cache, workspace, output, args, result):
    code = product_root(project)
    result['component'] = web_build.component(root, code, observed, repository, args.tools, output)
    result['publication'] = web_build.publish(root, code, result['component'], argv, env, output, workspace / 'published')
    result['tools'] = web_build.tool_bytes(root, repository)
    http_tools = getattr(args, 'http_tools', None)
    if not http_tools:
        preparation = workspace / 'http-preparation'; preparation.mkdir()
        http_tools = str(preparation)
        calibrated = SimpleNamespace(prepare=http_tools, repository=str(repository), http_tools=None)
        result['http_preparation'] = calibrate_http.prepare_with_home(calibrated, home)
    result['consumers'], result['contracts'] = {}, {}
    for name in ('a', 'b'):
        consumer = web_build.consume(root, code, name, workspace, result['publication'], repository, cache, home, env, args.tools)
        result['consumers'][name] = consumer
        result['contracts'][name] = web_http.contract(root, name, consumer, home, http_tools, output)
    require(set(result['consumers']) == {'a', 'b'}, 'PG_CONSUMER_COVERAGE', '必须真实验证两个独立消费者')
    result['packaged'] = web_http.packaged(root, result['consumers'], output)
    if args.check in ('all', 'dependency-risk-gate'):
        result['advisory'] = {}
        scan = output / 'web-advisory'; scan.mkdir()
        result['advisory']['component'] = web_advisory.scan(code, web_build.WEB, result['component'], argv, env, args.advisory_data, scan)
        for name, consumer in result['consumers'].items():
            scan = output / ('consumer-' + name + '-advisory'); scan.mkdir()
            result['advisory'][name] = web_advisory.scan(Path(consumer['project']), Path('app' if name == 'b' else '.'), consumer['observed'], consumer['argv'], env, args.advisory_data, scan)
    result['counterexamples'] = web_negative.verify(root, result['consumers'], result['publication'], home, env, workspace)
    result['ok'] = True
    return result
