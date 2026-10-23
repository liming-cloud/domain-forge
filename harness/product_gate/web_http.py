"""真实注册路由、生成合同、独立期望和可执行制品的 HTTP 验证。"""

import base64
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import time
from types import SimpleNamespace
import urllib.error
import urllib.request

from environment import require, digest
import calibrate_http
import calibrate_maven_source
import runtime
import environment


def contract(root, name, consumer, home, http_tools, output):
    """当前真实合同须匹配已固定的首版生成基线；不声称跨版本升级。"""
    app = Path(consumer['app'])
    scenarios = root / ('harness/product_gate/consumer-' + name + '-scenarios.json')
    definition, cases = calibrate_http.scenarios(scenarios)
    args = SimpleNamespace(project=str(app), maven_home=str(home), repository=consumer['repository'], http_tools=http_tools)
    calibrated = calibrate_http.libraries(args)
    build = consumer['observed']['build']
    before = digest(scenarios)
    with tempfile.TemporaryDirectory(prefix='domain-forge-r2-http-') as directory:
        temporary = Path(directory)
        document, classes = calibrate_http.capture(args, build, definition, cases, temporary)
        registered, generated, interactions = calibrate_http.observations(document, classes, definition, cases, ("/actuator/health", "/actuator/health/**"))
        require(generated.get('openapi') == '3.0.1' and generated.get('info', {}).get('version') == definition['version'],
                'PG_HTTP_VERSION', '生成 HTTP 合同没有绑定实际候选版本')
        calibrate_http.local_spec(generated)
        diagnostics = calibrate_http.coverage(registered, generated, definition)
        diagnostics.extend(calibrate_http.semantics(interactions, cases))
        diagnostics.extend(safe_messages(interactions, cases))
        initial = output / ('consumer-' + name + '-openapi.json')
        initial.write_text(json.dumps(generated, ensure_ascii=False, indent=2) + '\n')
        args.baseline = str(contract_baseline(root, name, generated))
        initial_hash = digest(initial)
        native = calibrate_http.validation(args, generated, interactions, temporary)
        diagnostics.extend(x for x in native['messages'] if x['level'] == 'ERROR')
        require(digest(initial) == initial_hash and digest(scenarios) == before, 'PG_HTTP_INPUT', '生成合同或独立期望在验证期间变化')
    for interaction in interactions:
        require('synthetic-secret' not in base64.b64decode(interaction['body_base64']).decode(errors='replace'),
                'PG_HTTP_SECRET', '错误响应暴露内部诊断或原始请求')
    calibrate_maven_source.unchanged_build(build, app)
    result = {'ok': not diagnostics, 'registered': registered, 'interactions': interactions, 'native': native,
              'generated': {'path': str(initial), 'sha256': initial_hash}, 'scenarios_sha256': before,
              'environment': calibrated, 'upgrade_compatibility_proven': False, 'diagnostics': diagnostics}
    (output / ('consumer-' + name + '-http.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    require(result['ok'], 'PG_HTTP_CONTRACT', '实际 HTTP 合同失败：' + str(output / ('consumer-' + name + '-http.json')))
    return result


def baseline_inputs(root):
    inputs = environment.sources(root)
    inputs.update({f'harness/product_gate/consumer-{name}-scenarios.json': digest(root / f'harness/product_gate/consumer-{name}-scenarios.json') for name in ('a', 'b')})
    return inputs


def contract_baseline(root, name, generated):
    directory = root / environment.PRODUCT / environment.CONTRACTS
    manifest = json.loads((directory / 'manifest.json').read_text())
    require(manifest.get('schema') == 1 and manifest.get('version') == '0.3.0-iam.2' and
            manifest.get('inputs') == baseline_inputs(root) and set(manifest.get('contracts', {})) == {'a', 'b'},
            'PG_HTTP_BASELINE', '首版合同未绑定当前真实输入或消费者不完整')
    path = directory / f'consumer-{name}-openapi.json'
    require(not path.is_symlink() and path.is_file() and digest(path) == manifest['contracts'][name]['sha256'] and
            json.loads(path.read_text()) == generated, 'PG_HTTP_BASELINE', '首版生成合同被修改或当前声明发生漂移')
    return path


def safe_messages(interactions, cases):
    """技术失败提示由独立场景固定，拒绝非 sentinel 的内部信息泄漏。"""
    messages = {'INVALID_REQUEST': '请求不合法', 'UNAUTHENTICATED': '需要身份认证',
                'FORBIDDEN': '无权执行此操作', 'SERVICE_UNAVAILABLE': '服务暂不可用', 'INTERNAL_ERROR': '服务处理失败'}
    return [{'code': 'PG_HTTP_SAFE_MESSAGE', 'case': case['id']} for actual, case in zip(interactions, cases)
            if case.get('code') in messages and actual.get('body', {}).get('message') != messages[case['code']]]


def artifact_binding(consumers):
    """每次运行前后重新绑定实际 JAR，拒绝以其他消费者替代构建结果。"""
    require(set(consumers) == {'a', 'b'}, 'PG_CONSUMER_COVERAGE', '打包验证必须包含两个消费者')
    result = {}
    for name, consumer in consumers.items():
        artifact = consumer['observed']['artifact']; path = Path(artifact['path'])
        require(path.is_file() and not path.is_symlink() and digest(path) == artifact['sha256'],
                'PG_ARTIFACT_BINDING', '打包运行制品与本次构建散列不符：' + name)
        result[name] = {'path': str(path), 'sha256': digest(path)}
    return result


def request(port, method, path, headers=None, body=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    req = urllib.request.Request(f'http://127.0.0.1:{port}' + path, data=body.encode() if body is not None else None,
                                 headers=headers or {}, method=method)
    try:
        response = opener.open(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, dict(response.headers.items()), response.read()


def start(artifact, port, log):
    stream = log.open('w')
    process = subprocess.Popen(['java', '-jar', artifact, '--server.port=' + str(port)], stdout=stream, stderr=subprocess.STDOUT,
                               cwd=Path(artifact).parent)
    try:
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            require(process.poll() is None, 'PG_CONSUMER_START', '消费者启动提前退出：' + str(log))
            try:
                status, _, body = request(port, 'GET', '/actuator/health')
                if status == 200 and json.loads(body) == {'status': 'UP'}:
                    return process, stream
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(.2)
        require(False, 'PG_CONSUMER_START', '消费者未就绪：' + str(log))
    except BaseException:
        runtime.stop(process); stream.close(); raise


def failure_diagnostic(case, text):
    """只核对本次请求新增日志；未知与扩展故障须可定位，正常拒绝不能误记系统故障。"""
    require('synthetic-secret' not in text, 'PG_HTTP_DIAGNOSTIC_SECRET', '内部诊断泄漏测试敏感内容')
    lines = [line for line in text.splitlines() if 'platform_http_failure ' in line]
    if case.get('code') != 'INTERNAL_ERROR':
        require(not lines, 'PG_HTTP_DIAGNOSTIC', '非系统故障被记为系统故障')
        return {'required': False, 'count': 0}
    require(len(lines) == 1, 'PG_HTTP_DIAGNOSTIC', '本次故障缺少或重复内部诊断')
    line = lines[0]
    path = case['request']['path']
    mapping = path.startswith('/mapping')
    kind = 'rejection-mapping' if mapping else 'unhandled'
    location = ('RejectionAdapter.map' if path.endswith('=throw') else 'RejectionMappings.apply') if mapping else 'ContractController.unknown'
    failure = 'IllegalArgumentException' if mapping and not path.endswith('=throw') else 'IllegalStateException'
    require(' ERROR ' in line and 'kind=' + kind in line and failure in line and
            re.search(re.escape(location) + r':\d+', line),
            'PG_HTTP_DIAGNOSTIC', '诊断缺少准确的故障类别、原因类型或代码位置')
    return {'required': True, 'count': 1, 'line': line}


def packaged(root, consumers, output):
    """A/B 同时从真实打包结果运行；逐例请求，停止 A 后仍请求 B。"""
    artifacts = artifact_binding(consumers)
    active, results = {}, {}
    try:
        for name in ('a', 'b'):
            port = runtime.free_port()
            while port in [x[2] for x in active.values()]:
                port = runtime.free_port()
            process, stream = start(consumers[name]['observed']['artifact']['path'], port, output / ('consumer-' + name + '-packaged.log'))
            active[name] = (process, stream, port)
            _, cases = calibrate_http.scenarios(root / ('harness/product_gate/consumer-' + name + '-scenarios.json'))
            interactions, internal_diagnostics = [], []
            log = output / ('consumer-' + name + '-packaged.log')
            for case in cases:
                offset = log.stat().st_size
                req = case['request']
                request_headers = dict(req['headers'], **{'X-F01-Probe': 'synthetic-secret-header'})
                status, headers, raw = request(port, req['method'], req['path'], request_headers, req['body'])
                with log.open('rb') as stream_log:
                    stream_log.seek(offset)
                    internal_diagnostics.append({'case': case['id'], **failure_diagnostic(case, stream_log.read().decode(errors='replace'))})
                interactions.append({'status': status, 'headers': {k.lower(): [v] for k, v in headers.items()},
                                     'body': json.loads(raw) if case['protocol'] == 'json' else raw.decode(errors='replace'),
                                     'body_base64': base64.b64encode(raw).decode()})
                require(b'synthetic-secret' not in raw, 'PG_HTTP_SECRET', '打包运行暴露诊断内容')
            diagnostics = calibrate_http.semantics(interactions, cases) + safe_messages(interactions, cases)
            require(not diagnostics, 'PG_PACKAGED_HTTP', '打包后的真实 HTTP 行为偏离独立期望：' + str(diagnostics))
            require(request(port, 'GET', '/missing')[0] == 404 and request(port, 'POST', '/success')[0] == 405,
                    'PG_HOST_HTTP', '未选中路由的宿主 HTTP 语义改变')
            require(request(port, 'GET', '/actuator/env')[0] == 404, 'PG_HOST_HTTP', '暴露未声明运维端点')
            results[name] = {'port': port, 'interactions': interactions, 'internal_diagnostics': internal_diagnostics,
                             'host_404_405': True, 'health_only': True}
        runtime.stop(active['a'][0])
        status, _, body = request(active['b'][2], 'GET', '/rejected')
        require(status == 200 and json.loads(body)['code'] == 'DECLINED', 'PG_CONSUMER_ISOLATION', '停止 A 后 B 不再可独立运行')
    finally:
        for process, stream, port in active.values():
            runtime.stop(process); stream.close()
            require(runtime.stopped(port), 'PG_RUNTIME_STOP', '消费者监听未释放')
    require(artifact_binding(consumers) == artifacts, 'PG_ARTIFACT_BINDING', '运行期间制品变化')
    return {'ok': True, 'consumers': results, 'artifacts': artifacts, 'b_survives_a_stop': True}
