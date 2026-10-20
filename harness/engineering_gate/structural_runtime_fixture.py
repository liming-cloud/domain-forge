"""阶段 2a 的临时 context 与真实 PostgreSQL 使用路径；不生成产品业务。"""

import hashlib
import json
import os
import pathlib
import queue
import subprocess
import sys
import time
import threading
import urllib.error
import urllib.request
from types import SimpleNamespace

import calibrate_roles
import calibrate_source


def sources():
    return {
        'bootstrap/App': '''package probe.service.bootstrap;
/** 仅装配当前 context 的临时启动配置。 */
@org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages="${probe.scan:probe.service}")
public class App {
  /** 将统一访问检查绑定到实际查询接口，供全部入口使用。 */
  @org.springframework.context.annotation.Bean
  @org.springframework.context.annotation.Primary
  public probe.service.application.query.Queries queries(probe.service.infrastructure.query.SqlQueries target) {
    return (probe.service.application.query.Queries) java.lang.reflect.Proxy.newProxyInstance(
        App.class.getClassLoader(), new Class<?>[] {probe.service.application.query.Queries.class},
        new probe.service.infrastructure.security.QueryGuard(target));
  }
}
''',
        'application/query/Queries': '''package probe.service.application.query;
/** 本上下文技术无关的有界查询合同。 */
public interface Queries {
  /** 读取当前服务端主体范围；请求范围不能授予权限，最多返回二十条。 */
  java.util.List<String> list(String requested, int limit);
}
''',
        'application/query/Subject': '''package probe.service.application.query;
/** 仅用于验证的受控身份源，不是产品认证机制。 */
public final class Subject {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
  private Subject() {}
  /** 绑定当前测试调用方，HTTP 和内部调用分别设置。 */
  public static void enter(String value) { CURRENT.set(value); }
  /** 清除请求绑定，避免跨调用串用。 */
  public static void clear() { CURRENT.remove(); }
  /** 返回服务端已绑定事实，不采用查询参数授予范围。 */
  public static String current() { return CURRENT.get(); }
  /** 默认拒绝；状态来源不可用不得沿用允许。 */
  public static void authorize() {
    if ("FAIL".equals(current())) { throw new IllegalStateException("identity unavailable"); }
    if (!"A".equals(current()) && !"B".equals(current())) { throw new SecurityException("denied"); }
  }
}
''',
        'application/query/ResourceReader': '''package probe.service.application.query;
/** 为资源授权提供本上下文事实，接口不泄漏数据访问技术。 */
public interface ResourceReader {
  /** 按标识读取资源事实，空值表示不存在。 */
  String read(int id);
}
''',
        'application/query/DetailQuery': '''package probe.service.application.query;
/** 存在资源授权决策时由应用用例拥有责任，不放进 SQL 适配器。 */
@org.springframework.stereotype.Service
public class DetailQuery {
  private final ResourceReader reader;
  /** 注入资源读取合同。 */
  public DetailQuery(ResourceReader reader) { this.reader = reader; }
  /** 读取后核对资源归属，不能把登录等同于资源授权。 */
  public String detail(int id) {
    Subject.authorize();
    String value = reader.read(id);
    if (value == null || !value.endsWith(":" + Subject.current())) { throw new SecurityException("denied"); }
    return value;
  }
}
''',
        'infrastructure/query/SqlQueries': '''package probe.service.infrastructure.query;
/** 直接投影本上下文数据的查询实现；故障开关只用于校准反例。 */
@org.springframework.stereotype.Component
public class SqlQueries implements probe.service.application.query.Queries, probe.service.application.query.ResourceReader {
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  /** 接收实际数据资源。 */
  public SqlQueries(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc = jdbc; }
  @Override
  public String read(int id) {
    java.util.List<String> rows = jdbc.queryForList("SELECT id::text || ':' || tenant FROM gate_items WHERE id = ?", String.class, id);
    return rows.isEmpty() ? null : rows.get(0);
  }
  @Override
  public java.util.List<String> list(String requested, int limit) {
    if (limit < 1) { throw new IllegalArgumentException("invalid limit"); }
    if (Boolean.getBoolean("probe.write")) { jdbc.update("UPDATE gate_items SET label = label || '!'"); }
    if (Boolean.getBoolean("probe.foreign")) { jdbc.queryForList("SELECT value FROM foreign_context.secret"); }
    String scope = Boolean.getBoolean("probe.spoof") ? requested : probe.service.application.query.Subject.current();
    java.util.List<String> rows = jdbc.queryForList("SELECT id::text || ':' || tenant FROM gate_items WHERE tenant = ? ORDER BY id LIMIT ?", String.class, scope, Math.min(limit, 20));
    if (Boolean.getBoolean("probe.nplus")) {
      for (String row : rows) { jdbc.queryForList("SELECT id FROM gate_items WHERE id = ?", Integer.parseInt(row.split(":")[0])); }
    }
    return rows;
  }
}
''',
        'infrastructure/security/QueryGuard': '''package probe.service.infrastructure.security;
/** 对查询用例统一执行访问检查，适用于 HTTP 和直接调用。 */
public class QueryGuard implements java.lang.reflect.InvocationHandler {
  private final probe.service.application.query.Queries target;
  /** 保留真正 SQL 读取实现。 */
  public QueryGuard(probe.service.application.query.Queries target) { this.target = target; }
  @Override
  public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
    if (!Boolean.getBoolean("probe.bypass")) { probe.service.application.query.Subject.authorize(); }
    try { return method.invoke(target, args); }
    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
  }
}
''',
        'interfaces/http/QueryEndpoint': '''package probe.service.interfaces.http;
/** HTTP 仅转换输入输出，依赖应用查询接口。 */
@org.springframework.stereotype.Component
public class QueryEndpoint implements com.sun.net.httpserver.HttpHandler {
  private final probe.service.application.query.Queries queries;
  private final probe.service.application.query.DetailQuery details;
  /** 所有入口共享同一受保护查询用例。 */
  public QueryEndpoint(probe.service.application.query.Queries queries, probe.service.application.query.DetailQuery details) { this.queries = queries; this.details = details; }
  @Override
  public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
    String token = exchange.getRequestHeaders().getFirst("X-Probe-Token");
    probe.service.application.query.Subject.enter(token);
    int status = 200;
    String body;
    try {
      String path = exchange.getRequestURI().getPath();
      body = path.equals("/items") ? String.join(",", queries.list("B", 100)) : details.detail(Integer.parseInt(path.substring("/items/".length())));
    }
    catch (SecurityException denied) { status = 403; body = "{\\"code\\":\\"FORBIDDEN\\",\\"message\\":\\"拒绝访问\\",\\"data\\":null}"; }
    catch (RuntimeException failure) { status = 503; body = "{\\"code\\":\\"SERVICE_UNAVAILABLE\\",\\"message\\":\\"服务暂不可用\\",\\"data\\":null}"; System.err.println("QUERY_DEPENDENCY_UNAVAILABLE " + failure.getClass().getName()); }
    finally { probe.service.application.query.Subject.clear(); }
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
''',
        'bootstrap/HttpLifecycle': '''package probe.service.bootstrap;
/** 只管理临时 HTTP 入口生命周期，不执行查询。 */
@org.springframework.stereotype.Component
public class HttpLifecycle implements AutoCloseable {
  private final com.sun.net.httpserver.HttpServer server;
  /** 装配接口适配器并绑定环回临时端口。 */
  public HttpLifecycle(probe.service.interfaces.http.QueryEndpoint endpoint) throws java.io.IOException {
    server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/items", endpoint);
    server.start();
  }
  /** 供独立运行观察器取得实际端口。 */
  public int port() { return server.getAddress().getPort(); }
  @Override public void close() { server.stop(0); }
}
''',
    }


OBSERVER = '''import java.io.BufferedReader;
import java.io.InputStreamReader;
/** 独立运行观察器直接调用公开查询 Bean；不参与被测结构声明。 */
public class QueryRuntimeProbe {
  public static void main(String[] args) throws Exception {
    org.springframework.boot.SpringApplication app = new org.springframework.boot.SpringApplication(probe.service.bootstrap.App.class);
    app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    try (var context = app.run(args)) {
      System.out.println("READY " + context.getBean(probe.service.bootstrap.HttpLifecycle.class).port() + " " + context.containsBean("outsideMarker"));
      var queries = context.getBean(probe.service.application.query.Queries.class);
      var input = new BufferedReader(new InputStreamReader(System.in));
      String token;
      while ((token = input.readLine()) != null && !token.equals("STOP")) {
        probe.service.application.query.Subject.enter(token.startsWith("DETAIL:") ? "A" : token);
        try {
          String body = token.startsWith("DETAIL:") ? context.getBean(probe.service.application.query.DetailQuery.class).detail(Integer.parseInt(token.substring(7))) : String.join(",", queries.list("B", 100));
          System.out.println("RESULT 200 " + body);
        }
        catch (SecurityException failure) { System.out.println("RESULT 403 denied"); }
        catch (RuntimeException failure) { System.out.println("RESULT 503 unavailable"); }
        finally { probe.service.application.query.Subject.clear(); }
      }
    }
  }
}
'''


def read_line(process, prefix, timeout=15):
    """有界读取实际子进程输出，不把尚未就绪误判为业务反例。"""
    deadline = time.monotonic() + timeout
    lines = []
    if not hasattr(process, 'observed_lines'):
        process.observed_lines = queue.Queue()
        def collect():
            for line in process.stdout:
                process.observed_lines.put(line)
            process.observed_lines.put('')
        process.collect_thread = threading.Thread(target=collect, daemon=True)
        process.collect_thread.start()
    while time.monotonic() < deadline:
        try:
            line = process.observed_lines.get(timeout=max(0, deadline-time.monotonic()))
        except queue.Empty:
            break
        if not line:
            break
        lines.append(line)
        if line.startswith(prefix):
            return line.strip(), ''.join(lines)
    raise AssertionError('运行观察未得到 ' + prefix + ': ' + ''.join(lines)[-2000:])


def verify(case):
    """复用已有本地数据库与锁定依赖，验证结构、扫描、两种入口和真实 SQL。"""
    import re
    import shutil
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'product_gate'))
    import build as product_build

    def native_logs():
        result = subprocess.run(['docker', '--host', case.endpoint, 'logs', case.container], capture_output=True, text=True, timeout=30)
        case.assertEqual(result.returncode, 0)
        return result.stdout + result.stderr

    # 本函数由已有迁移组执行；该组已经准备原生迁移依赖、镜像及编译工具。
    for directory in ('src/test-named-production/java', 'verification/java'):
        shutil.rmtree(case.project / directory)
    pom = case.project / 'pom.xml'
    direct = ''.join('<dependency><groupId>' + group + '</groupId><artifactId>' + artifact + '</artifactId></dependency>'
                     for group, artifact in [('org.springframework.boot', 'spring-boot'),
                         ('org.springframework.boot', 'spring-boot-autoconfigure'),
                         ('org.springframework', 'spring-context'), ('org.springframework', 'spring-jdbc')])
    pom.write_text(pom.read_text().replace('</dependencies><build>', direct + '</dependencies><build>'))
    for name, source in sources().items():
        case.write('src/test-named-production/java/probe/service/' + name + '.java', source)
    case.write('src/test-named-production/java/probe/sibling/OutsideMarker.java', '''package probe.sibling;
/** 兄弟根包不应被当前服务扫描。 */
@org.springframework.stereotype.Component
public class OutsideMarker {}
''')
    case.write('verification/java/probe/service/QueryTest.java', '''package probe.service.application.query;
/** 验证源码用途与测试归属。 */
class QueryTest {}
''')
    case.write('src/main/resources/db/migration/V1__init.sql', '''CREATE TABLE gate_items(id INTEGER PRIMARY KEY, tenant TEXT NOT NULL, label TEXT NOT NULL);
INSERT INTO gate_items SELECT i, CASE WHEN i <= 25 THEN 'A' ELSE 'B' END, 'original' FROM generate_series(1,50) i;
CREATE SCHEMA foreign_context;
CREATE TABLE foreign_context.secret(value TEXT);
INSERT INTO foreign_context.secret VALUES ('private');
''')
    # 由 PostgreSQL 本身记录实际执行，查询实现不能自报次数。
    case.docker('exec', case.container, 'psql', '-U', 'gate', '-d', 'gate', '-c', "ALTER ROLE gate SET log_statement = 'all';")
    tools = os.environ['DOMAIN_FORGE_GATE_TOOLS']
    paths, _ = calibrate_source.toolchain(tools)
    formatter = pathlib.Path(tools) / 'google-java-format-1.37.0-all-deps.jar'
    java_files = list(case.project.rglob('*.java'))
    p = subprocess.run(['java', '-jar', str(formatter), '--replace', *map(str, java_files)], capture_output=True, text=True, timeout=30)
    case.assertEqual(p.returncode, 0, p.stderr)
    contract = 'probe.service.application.query.Queries.list(java.lang.String,int):java.util.List'
    implementation = 'probe.service.infrastructure.query.SqlQueries.list(java.lang.String,int):java.util.List'
    resource = 'probe.service.application.query.ResourceReader.read(int):java.lang.String'
    modules = {'schema': 4, 'modules': [dict(id='service', package='probe.service', kind='context', depends_on=[],
        contract_types=['probe.service.application.query.Queries', 'probe.service.application.query.ResourceReader'], contract_members=[contract, resource], api_categories={}, api_uses={},
        query_bindings=[dict(contract=contract, implementation=implementation), dict(contract=resource, implementation='probe.service.infrastructure.query.SqlQueries.read(int):java.lang.String')]), dict(id='sibling', package='probe.sibling',
        kind='assembly', depends_on=[], contract_types=[], contract_members=[], api_categories={}, api_uses={}, query_bindings=[])]}
    declaration = case.root / 'modules.json'; declaration.write_text(json.dumps(modules))
    approved = hashlib.sha256(declaration.read_bytes()).hexdigest()
    args = SimpleNamespace(project=str(case.project), declaration=str(declaration), contract_sha256=approved,
                           maven_home=case.maven, repository=case.repository, tools=tools)
    report = calibrate_roles.probe(args)
    case.assertTrue(report['ok'], report['diagnostics'])
    # 真正产品入口复用同一原生对象；不是另写一份只在测试中调用的规则。
    result = product_build.quality(pathlib.Path(__file__).resolve().parents[2], case.project,
                                   {'build': report['build']}, tools, str(declaration), approved)
    case.assertTrue(result['ok'], result['diagnostics'])
    case.assertEqual(report['role_bindings']['probe.service.bootstrap.App'], 'bootstrap')
    output = case.root / 'observer'; output.mkdir()
    source = output / 'QueryRuntimeProbe.java'; source.write_text(OBSERVER)
    classpath = os.pathsep.join(dict.fromkeys(path for compilation in report['build']['compilations'] for path in compilation['classpath']))
    p = subprocess.run(['javac', '--release', '21', '-cp', classpath, '-d', str(output), str(source)], capture_output=True, text=True, timeout=30)
    case.assertEqual(p.returncode, 0, p.stderr)
    env = os.environ.copy()
    env.update(DF_GATE_JDBC_URL=case.credentials['jdbc_url'], DF_GATE_DB_USER='gate', DF_GATE_DB_PASSWORD=case.password)
    all_results = []
    for variant in ('valid', 'write', 'nplus', 'spoof', 'bypass', 'foreign', 'broad', 'narrow'):
        flags = [] if variant in ('valid', 'broad', 'narrow') else ['-Dprobe.' + variant + '=true']
        scan = ['--probe.scan=probe' + ('.service.bootstrap' if variant == 'narrow' else '')] if variant in ('broad', 'narrow') else []
        errors = output / (variant + '.stderr')
        with errors.open('w') as stderr:
            process = subprocess.Popen(['java', *flags, '-cp', str(output) + os.pathsep + classpath, 'QueryRuntimeProbe', *scan],
                cwd=case.project, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, text=True, bufsize=1)
            try:
                if variant == 'narrow':
                    stdout, _ = process.communicate(timeout=30)
                    case.assertNotEqual(process.returncode, 0)
                    case.assertIn('No qualifying bean', stdout + errors.read_text())
                    all_results.append(dict(variant=variant, rejected='missing context bean'))
                    continue
                ready, startup = read_line(process, 'READY ')
                (output/(variant + '-startup.log')).write_text(startup)
                port, outside = ready.split()[1:]
                if variant == 'broad':
                    case.assertEqual(outside, 'true')
                    all_results.append(dict(variant=variant, rejected='sibling scanned'))
                    continue
                case.assertEqual(outside, 'false')
                snapshots = []
                for mode in ('http', 'direct'):
                    for token in ('A', 'DENIED', 'FAIL'):
                        before = native_logs()
                        data_before = case.docker('exec', case.container, 'psql', '-U', 'gate', '-d', 'gate', '-At', '-c', 'COPY gate_items TO STDOUT;')
                        if mode == 'http':
                            request = urllib.request.Request('http://127.0.0.1:' + port + '/items', headers={'X-Probe-Token': token})
                            try:
                                with urllib.request.urlopen(request, timeout=5) as response: status, body = response.status, response.read().decode()
                            except urllib.error.HTTPError as failure:
                                status, body = failure.code, failure.read().decode()
                                failure.close()
                        else:
                            process.stdin.write(token + '\n'); process.stdin.flush()
                            line, _ = read_line(process, 'RESULT ')
                            status, _, body = line.removeprefix('RESULT ').partition(' '); status = int(status)
                        data_after = case.docker('exec', case.container, 'psql', '-U', 'gate', '-d', 'gate', '-At', '-c', 'COPY gate_items TO STDOUT;')
                        native = native_logs()[len(before):]
                        statements = re.findall(r'LOG:  (?:execute [^:]+|statement): (.*)', native)
                        queries = [sql for sql in statements if 'gate_items' in sql or 'foreign_context.secret' in sql]
                        queries = [sql for sql in queries if not sql.startswith('COPY ')]
                        expected = 200 if token == 'A' else (403 if token == 'DENIED' else 503)
                        valid = status == expected and data_before == data_after
                        if token == 'A':
                            valid = valid and body == ','.join(str(i) + ':A' for i in range(1,21)) and len(queries) == 1 and queries[0].startswith('SELECT ')
                        else:
                            valid = valid and not queries
                            if mode == 'http' and status == expected:
                                envelope = json.loads(body)
                                case.assertEqual(envelope, dict(code='FORBIDDEN' if token == 'DENIED' else 'SERVICE_UNAVAILABLE', message='拒绝访问' if token == 'DENIED' else '服务暂不可用', data=None))
                        snapshots.append(dict(mode=mode, token=token, status=status, valid=valid, statements=queries,
                                              native_log=native, before_rows=data_before, after_rows=data_after,
                                              state_unchanged=data_before==data_after))
                case.assertEqual(all(item['valid'] for item in snapshots), variant == 'valid', snapshots)
                if variant == 'valid':
                    case.assertIn('QUERY_DEPENDENCY_UNAVAILABLE', errors.read_text())
                    for mode in ('http', 'direct'):
                        for identifier, expected in ((1, 200), (26, 403)):
                            if mode == 'direct':
                                process.stdin.write('DETAIL:' + str(identifier) + '\n'); process.stdin.flush()
                                line, _ = read_line(process, 'RESULT ')
                                status, _, body = line.removeprefix('RESULT ').partition(' '); status = int(status)
                            else:
                                request = urllib.request.Request('http://127.0.0.1:' + port + '/items/' + str(identifier), headers={'X-Probe-Token': 'A'})
                                try:
                                    with urllib.request.urlopen(request, timeout=5) as response: status, body = response.status, response.read().decode()
                                except urllib.error.HTTPError as failure:
                                    status, body = failure.code, failure.read().decode()
                                    failure.close()
                            case.assertEqual(status, expected)
                            if expected == 200: case.assertEqual(body, '1:A')
                            else: case.assertNotIn('26:B', body)
                            snapshots.append(dict(mode=mode, resource=identifier, status=status, valid=True))
                all_results.append(dict(variant=variant, observations=snapshots))
            finally:
                if process.poll() is None:
                    process.stdin.write('STOP\n'); process.stdin.flush()
                    try: process.wait(timeout=10)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()
                if hasattr(process, 'collect_thread'): process.collect_thread.join(timeout=2)
                process.stdin.close()
                process.stdout.close()
    (output/'results.json').write_text(json.dumps(all_results,ensure_ascii=False,indent=2)+'\n')
    evidence = os.environ.get('DOMAIN_FORGE_STRUCTURAL_EVIDENCE')
    if evidence:
        target = pathlib.Path(evidence)
        case.assertFalse(target.exists())
        shutil.copytree(output, target)
        shutil.copytree(case.project, target/'project', ignore=shutil.ignore_patterns('target'))
        (target/'structure.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        (target/'quality.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
