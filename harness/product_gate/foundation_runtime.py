"""在隔离 PostgreSQL 与真实 JAR 上核对模板运行合同，不接受模拟健康结果。"""

import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request

from environment import digest, require
import calibrate_flyway


def docker(*arguments, endpoint=None):
    """只调用本机 Docker；返回值由场景筛选，不把 inspect 的环境秘密写入报告。"""
    calibrate_flyway.check_docker_routing()
    result = subprocess.run(['docker', '--host', endpoint or calibrate_flyway.local_endpoint(), *map(str, arguments)], capture_output=True, text=True, timeout=30)
    require(result.returncode == 0, 'PG_DATABASE_ENVIRONMENT', 'Docker 原生操作失败：' + result.stderr[:500])
    return result.stdout.strip()


def free_port():
    """为本轮回环进程选择临时端口；启动失败仍须显式报告。"""
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def response(port, path, timeout=5):
    """读取实际 HTTP，包括非成功状态；不把异常替换为空成功。"""
    try:
        with urllib.request.urlopen('http://127.0.0.1:' + str(port) + path, timeout=timeout) as stream:
            return stream.status, json.load(stream)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


class Database:
    """仅管理本轮带所有权标签的临时容器，数据库文件不挂载宿主目录。"""

    def __init__(self, root, workspace):
        self.image = json.loads((root / 'harness/product_gate/foundation-baseline.json').read_text())['postgres_image']
        self.workspace = workspace
        self.container = None
        self.password = secrets.token_hex(24)

    def __enter__(self):
        self.endpoint = calibrate_flyway.local_endpoint()
        secret = self.workspace / 'database.env'
        secret.write_text('POSTGRES_DB=gate\nPOSTGRES_USER=gate\nPOSTGRES_PASSWORD=' + self.password + '\n')
        secret.chmod(0o600)
        self.container = docker('run', '-d', '--rm', '--label', 'org.domainforge.product-verification=foundation',
                                '--tmpfs', '/var/lib/postgresql', '-p', '127.0.0.1::5432',
                                '--env-file', secret, self.image, '-c', 'log_statement=all', endpoint=self.endpoint)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                result = subprocess.run(['docker', '--host', self.endpoint, 'exec', self.container, 'pg_isready', '-h', '127.0.0.1', '-U', 'gate', '-d', 'gate'], capture_output=True, timeout=5)
                if result.returncode == 0:
                    break
                time.sleep(.2)
            require(result.returncode == 0, 'PG_DATABASE_ENVIRONMENT', 'PostgreSQL 未在边界内就绪')
            state = json.loads(docker('inspect', self.container, endpoint=self.endpoint))[0]
            require(state['Config']['Image'] == self.image, 'PG_DATABASE_VERSION', '实际容器镜像引用偏离固定摘要')
            self.image_id = state['Image']
            port = state['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']
            self.url = 'jdbc:postgresql://127.0.0.1:' + port + '/gate'
            require(self.sql('SHOW server_version').split()[0] == '18.4', 'PG_DATABASE_VERSION', '实际数据库版本偏离固定镜像')
            return self
        except Exception:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *exception):
        if self.container:
            docker('rm', '-f', self.container, endpoint=self.endpoint)
        (self.workspace / 'database.env').unlink(missing_ok=True)

    def sql(self, statement):
        """仅执行验证器自身固定 SQL，不执行模板参数提供的命令。"""
        return docker('exec', self.container, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'gate', '-d', 'gate', '-At', '-c', statement, endpoint=self.endpoint)

    def environment(self, original, schema='public'):
        return dict(original, DB_URL=self.url + '?currentSchema=' + schema, DB_USER='gate', DB_PASSWORD=self.password)


class Application:
    """管理真实打包进程及其诊断；退出后保留日志，检查凭据不泄漏。"""

    def __init__(self, artifact, output, env, name, arguments=()):
        self.artifact, self.output, self.name = artifact, output, name
        self.port, self.management = free_port(), free_port()
        self.env = dict(env, SERVER_PORT=str(self.port), MANAGEMENT_PORT=str(self.management))
        self.arguments = arguments

    def __enter__(self):
        self.log = self.output / (self.name + '.log')
        self.stream = self.log.open('w')
        self.process = subprocess.Popen(['java', '-jar', str(self.artifact), *self.arguments], env=self.env,
                                        stdout=self.stream, stderr=subprocess.STDOUT)
        return self

    def __exit__(self, *exception):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill(); self.process.wait(timeout=5)
        self.stream.close()
        text = self.log.read_text(errors='replace')
        for name in ('DB_PASSWORD',):
            require(not self.env.get(name) or self.env[name] not in text,
                    'PG_DIAGNOSTIC_SECRET', '应用日志包含验证凭据')

    def ready(self):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            require(self.process.poll() is None, 'PG_APPLICATION_START', '应用启动提前退出：' + str(self.log))
            try:
                status, body = response(self.management, '/actuator/health/readiness')
                if status == 200 and body == {'status': 'UP'}:
                    return {'status': status, 'body': body}
            except (OSError, ValueError):
                pass
            time.sleep(.2)
        require(False, 'PG_APPLICATION_START', '应用未在边界内就绪：' + str(self.log))

    def failed(self):
        try:
            code = self.process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            require(False, 'PG_NEGATIVE_MISSED', '错误配置未使启动失败：' + str(self.log))
        require(code != 0 and 'Started Application' not in self.log.read_text(),
                'PG_NEGATIVE_MISSED', '错误配置仍启动成功')
        return {'exit_code': code, 'log': str(self.log)}


def bare(artifact, output, env, database, name):
    """裸模板无业务表／接口；只证明真实数据库、健康与必要指标。"""
    with Application(artifact, output, database.environment(env), name) as app:
        ready = app.ready()
        require(response(app.management, '/actuator/health/liveness') == (200, {'status': 'UP'}),
                'PG_HEALTH', 'liveness 未表达运行状态')
        require(response(app.port, '/actuator/health')[0] == 404, 'PG_MANAGEMENT_BOUNDARY', '管理端点泄漏至业务端口')
        status, metrics = response(app.management, '/actuator/metrics')
        (output / (name + '-metrics.json')).write_text(json.dumps(metrics, indent=2) + '\n')
        required = {'jvm.memory.used', 'hikaricp.connections.max', 'http.server.requests', 'domainforge.logging.degraded'}
        # 业务端口的真实请求已经产生 HTTP 指标。
        require(status == 200 and required <= set(metrics['names']), 'PG_METRICS', '缺少真实 JVM／HTTP／连接池／日志指标')
        require(response(app.port, '/actuator/health')[0] == 404, 'PG_MANAGEMENT_BOUNDARY', '管理端点泄漏至业务端口')
        require(response(app.management, '/actuator/env')[0] == 404, 'PG_MANAGEMENT_BOUNDARY', '管理端点暴露未声明内容')
        tables = database.sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").splitlines()
        require(set(tables) <= {'flyway_schema_history'}, 'PG_TEMPLATE_BUSINESS', '裸模板创建了业务表')
        require(response(app.management, '/actuator/metrics/hikaricp.connections.max')[1]['measurements'][0]['value'] == 10,
                'PG_CONFIGURATION', '实际连接池最大值偏离模板')
        require(response(app.management, '/actuator/metrics/domainforge.logging.degraded')[1]['measurements'][0]['value'] == 0,
                'PG_OBSERVATION', '默认日志出口未正常装配')
        result = {'ready': ready, 'tables': tables, 'metrics': sorted(required), 'jar_sha256': digest(artifact)}
    lines = [json.loads(line) for line in app.log.read_text().splitlines() if line.startswith('{')]
    require(lines and all(line.get('log', {}).get('level') and 'message' in line and line.get('ecs', {}).get('version') == '8.11' for line in lines),
            'PG_OBSERVATION', '没有实际结构化日志')
    result['structured_lines'] = len(lines)
    return result


def database_log(database):
    """服务器记录实际执行语句，不能只相信 Mapper 调用计数。"""
    result = subprocess.run(['docker', '--host', database.endpoint, 'logs', database.container], capture_output=True, text=True, timeout=30)
    require(result.returncode == 0, 'PG_DATABASE_OBSERVER', '缺少数据库执行日志')
    return result.stdout + result.stderr


def query_case(app, database, output, name, value='match', limit=20, order='asc', expected=None):
    """核对响应、原生 SQL 次数和表值；N+1 与隐式写入分别触发失败。"""
    import re
    import urllib.parse
    before_rows = database.sql('SELECT id,value FROM probe_entry ORDER BY id')
    before_log = database_log(database)
    started = time.monotonic()
    status, body = response(app.port, '/fixture/records?' + urllib.parse.urlencode({'value': value, 'limit': limit, 'order': order}))
    elapsed = time.monotonic() - started
    after_log = database_log(database)
    require(after_log.startswith(before_log), 'PG_DATABASE_OBSERVER', '原生 SQL 日志前缀发生变化')
    delta = after_log[len(before_log):]
    statements = [match[1].strip() for match in re.findall(r'LOG:\s+(execute [^:]+|statement):\s*(.*)', delta)
                  if 'probe_entry' in match[1].lower()]
    after_rows = database.sql('SELECT id,value FROM probe_entry ORDER BY id')
    result = {'status': status, 'body': body, 'seconds': round(elapsed, 3), 'statements': statements,
              'rows_unchanged': before_rows == after_rows}
    (output / (name + '.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    (output / (name + '-database.log')).write_text(delta)
    require(status == 200 and body['code'] == 'SUCCESS' and len(body['data']) <= limit,
            'PG_QUERY_RESULT', '查询未返回受限成功视图')
    require(len(statements) == 1 and statements[0].upper().startswith('SELECT ') and
            '$1' in statements[0] and 'LIMIT' in statements[0].upper(),
            'PG_QUERY_SQL', '查询不是一次真实参数化有界 SELECT')
    require(before_rows == after_rows, 'PG_QUERY_WRITE', '纯查询改变了数据库业务行')
    if expected is not None:
        require([row['id'] for row in body['data']] == expected, 'PG_QUERY_ORDER', '查询结果或固定排序不符')
    return result


def queries(artifact, output, env, database):
    """合法短路径的真实 SQL 与参数边界；裸模板没有此验证接口。"""
    database.sql("DELETE FROM probe_entry; INSERT INTO probe_entry SELECT n,'match' FROM generate_series(1,60) n")
    with Application(artifact, output, database.environment(env), 'fixture-query') as app:
        app.ready()
        result = {
            'ascending': query_case(app, database, output, 'query-ascending', expected=list(range(1,21))),
            'descending': query_case(app, database, output, 'query-descending', limit=3, order='desc', expected=[60,59,58]),
            'binding': query_case(app, database, output, 'query-binding', value="match' OR 1=1 --", expected=[]),
            'empty': query_case(app, database, output, 'query-empty', value='absent', expected=[])}
        for suffix in ('value=match&limit=21&order=asc', 'value=match&limit=20&order=id;DROP%20TABLE%20probe_entry'):
            status, body = response(app.port, '/fixture/records?' + suffix)
            require(status == 400 and body['code'] == 'INVALID_REQUEST', 'PG_QUERY_INPUT', '非法查询边界未拒绝')
        result['dependency_failure'] = dependency_failure(app, database, output)
    return result


def dependency_failure(app, database, output):
    """暂停数据库连接后检查两个健康职责，恢复后必须重新执行真实 SQL。"""
    docker('pause', database.container, endpoint=database.endpoint)
    started = time.monotonic()
    try:
        require(response(app.management, '/actuator/health/liveness') == (200, {'status':'UP'}),
                'PG_HEALTH', '数据库故障错误地改变了 liveness')
        status, body = response(app.management, '/actuator/health/readiness', timeout=12)
        (output / 'database-down-response.json').write_text(json.dumps({'status':status,'body':body},indent=2)+'\n')
        require(status == 503 and body == {'status':'DOWN'}, 'PG_HEALTH', '真实断库未使 readiness 失败')
        duration = time.monotonic() - started
        require(duration < 12, 'PG_TIMEOUT_BOUNDARY', '依赖失败超过有限等待边界')
    finally:
        docker('unpause', database.container, endpoint=database.endpoint)
    app.ready()
    result = query_case(app, database, output, 'query-after-recovery', limit=1, expected=[1])
    return {'readiness': body, 'liveness': 'UP', 'failure_seconds': round(duration,3), 'recovery': result}


def startup_failures(artifact, output, env, database):
    """失败配置必须终止真实 JAR，保留原始日志及确切触发原因。"""
    result = {}
    base = database.environment(env)
    cases = [('missing-url', {key:value for key,value in base.items() if key != 'DB_URL'}, (), 'DB_URL'),
             ('missing-user', {key:value for key,value in base.items() if key != 'DB_USER'}, (), 'password authentication failed'),
             ('missing-password', {key:value for key,value in base.items() if key != 'DB_PASSWORD'}, (), 'password authentication failed'),
             ('wrong-credentials', dict(base, DB_PASSWORD='synthetic-wrong-' + secrets.token_hex(16)), (), 'password authentication failed'),
             ('invalid-pool', base, ('--spring.datasource.hikari.maximum-pool-size=0',), 'maxPoolSize'),
             ('invalid-pagination', base, ('--domainforge.persistence.max-page-size=0',), '分页上限')]
    for name, settings, arguments, reason in cases:
        with Application(artifact, output, settings, name, arguments) as app:
            result[name] = app.failed()
        require(reason.lower() in app.log.read_text().lower(), 'PG_NEGATIVE_CAUSE', '启动失败原因未命中：' + name)
    return result


def resource_mismatch(artifact, output, env, database):
    """错误迁移位置必须在执行任何 DDL 前失败，不能只断言最终未就绪。"""
    database.sql('CREATE SCHEMA wrong_target')
    before = database.sql("SELECT tablename FROM pg_tables WHERE schemaname='wrong_target'")
    settings = dict(database.environment(env), SPRING_FLYWAY_URL=database.url + '?currentSchema=wrong_target',
                    SPRING_FLYWAY_USER='gate', SPRING_FLYWAY_PASSWORD=database.password)
    with Application(artifact, output, settings, 'migration-resource-mismatch') as app:
        result = app.failed()
    after = database.sql("SELECT tablename FROM pg_tables WHERE schemaname='wrong_target'")
    require('Flyway 必须使用明确绑定的 dataSource' in app.log.read_text() and before == after == '',
            'PG_MIGRATION_RESOURCE', '错误迁移资源未在写入前被拒绝')
    return dict(result, tables_before=[], tables_after=[])


def migration_permission(artifact, output, env, database):
    """只授予连接和 schema 使用权，真实拒绝 Flyway 建表；秘密不进入数据库日志。"""
    script = "SET log_statement='none';\nCREATE ROLE migration_reader LOGIN PASSWORD '" + database.password + "';\nCREATE SCHEMA restricted;\nGRANT USAGE ON SCHEMA restricted TO migration_reader;\n"
    execution = subprocess.run(['docker', '--host', database.endpoint, 'exec', '-i', database.container, 'psql', '-v', 'ON_ERROR_STOP=1',
                                '-U', 'gate', '-d', 'gate'], input=script, capture_output=True, text=True, timeout=30)
    require(execution.returncode == 0, 'PG_DATABASE_ENVIRONMENT', '无法准备有限权限迁移反例')
    settings = database.environment(env, 'restricted'); settings['DB_USER'] = 'migration_reader'
    with Application(artifact, output, settings, 'migration-permission') as app:
        result = app.failed()
    require('permission denied' in app.log.read_text().lower() and
            database.sql("SELECT count(*) FROM pg_tables WHERE schemaname='restricted'") == '0',
            'PG_MIGRATION_PERMISSION', '无权限迁移没有被真实拒绝')
    require(database.password not in database_log(database), 'PG_DIAGNOSTIC_SECRET', '数据库日志含测试秘密')
    return result


def migrations(artifact, fixture, output, env, database):
    """同一已验证 JAR 在原生 Flyway 下执行重复、升级、冲突及恢复；不自动 repair。"""
    import shutil
    source = fixture / 'src/main/resources/db/migration/V1__verification.sql'
    location = output / 'migration-inputs'; location.mkdir()
    shutil.copy2(source, location / source.name)
    arguments = ('--spring.flyway.locations=filesystem:' + str(location),)
    before = database.sql('SELECT version,checksum,installed_rank FROM flyway_schema_history ORDER BY installed_rank')
    with Application(artifact, output, database.environment(env), 'migration-repeat', arguments) as app:
        app.ready()
    require(database.sql('SELECT version,checksum,installed_rank FROM flyway_schema_history ORDER BY installed_rank') == before,
            'PG_MIGRATION_REPEAT', '已应用迁移被重复执行')
    shutil.copy2(fixture / 'migrations/V2__verification.sql', location / 'V2__verification.sql')
    with Application(artifact, output, database.environment(env), 'migration-upgrade', arguments) as app:
        app.ready()
    require(database.sql("SELECT count(*) FROM flyway_schema_history WHERE success AND version='2'") == '1' and
            database.sql("SELECT count(*) FROM information_schema.columns WHERE table_name='probe_entry' AND column_name='verified'") == '1',
            'PG_MIGRATION_UPGRADE', 'V2 没有真实升级')
    original = (location / source.name).read_text()
    (location / source.name).write_text(original + '-- changed checksum\n')
    with Application(artifact, output, database.environment(env), 'migration-checksum', arguments) as app:
        checksum = app.failed()
    require('checksum mismatch' in app.log.read_text().lower(), 'PG_NEGATIVE_CAUSE', '校验和冲突未由 Flyway 拒绝')
    (location / source.name).write_text(original)
    failure = location / 'V3__verification.sql'; failure.write_text('CREATE TABLE broken (id BIGINT PRIMARY KEY); THIS IS INVALID SQL;\n')
    with Application(artifact, output, database.environment(env), 'migration-sql-failure', arguments) as app:
        invalid = app.failed()
    require('syntax error' in app.log.read_text().lower() and database.sql("SELECT to_regclass('public.broken')") == '',
            'PG_MIGRATION_ATOMICITY', '失败迁移没有原子回滚')
    failure.unlink()
    with Application(artifact, output, database.environment(env), 'migration-recovery', arguments) as app:
        ready = app.ready()
    require(database.sql('SELECT count(*) FROM probe_entry') == '60', 'PG_MIGRATION_RECOVERY', '恢复后数据库内容不可用')
    permission = migration_permission(artifact, output, env, database)
    return {'permission_failure': permission, 'repeat_unchanged': True, 'upgraded_to': '2', 'checksum_failure': checksum,
            'execution_failure': invalid, 'recovery': ready,
            'inputs': {path.name:digest(path) for path in location.iterdir()}}
