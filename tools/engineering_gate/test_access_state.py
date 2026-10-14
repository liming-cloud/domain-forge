"""访问具体适配的真实数据库与独立进程反例，不使用内存服务代替原生接入。"""

import json
import os
import pathlib
import secrets
import subprocess
import sys
import tempfile
import types
import re
import time
import unittest
import uuid

import test_flyway


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_access_state.py"
JAVA = ("AccessStateFixture.java", "AccessStateStore.java", "AccessStateSecurity.java")


class AccessStateFixtures(test_flyway.FlywayFixtures):
    def setUp(self):
        global native
        import access_state_environment as native
        super().setUp()
        self.assertTrue(os.environ.get("DOMAIN_FORGE_GATE_ACCESS_STATE_TOOLS"), "须准备真实 Redis 与原生依赖")
        self.state = self.root / "access-state.json"
        self.connections = self.root / "access-connections.json"
        self.start_redis()
        self.sql('''CREATE TABLE gate_account(project TEXT NOT NULL,id BIGINT NOT NULL,hash TEXT NOT NULL,active BOOLEAN NOT NULL,
permissions TEXT NOT NULL,scope INTEGER NOT NULL,revision INTEGER NOT NULL,roles TEXT NOT NULL,PRIMARY KEY(project,id));
CREATE TABLE gate_resource(project TEXT NOT NULL,id BIGINT NOT NULL,owner BIGINT NOT NULL,department INTEGER NOT NULL,value TEXT NOT NULL,PRIMARY KEY(project,id));
CREATE TABLE gate_audit(event UUID PRIMARY KEY,project TEXT NOT NULL,actor BIGINT NOT NULL,subject BIGINT NOT NULL,
action TEXT NOT NULL,result TEXT NOT NULL,partial BOOLEAN NOT NULL,source TEXT NOT NULL,happened_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
original_actor BIGINT,original_time TIMESTAMPTZ);''')

    def sql(self, query):
        return self.docker("exec", self.container, "psql", "-h", "127.0.0.1", "-U", "gate", "-d", "gate", "-At", "-v", "ON_ERROR_STOP=1", "-c", query)

    def start_redis(self):
        password = secrets.token_hex(24)
        env = self.root / "redis.env"
        env.write_text("REDISCLI_AUTH=" + password + "\n", encoding="utf-8")
        env.chmod(0o600)
        self.redis_container = self.docker("run", "-d", "--rm", "--name", "domain-forge-access-" + uuid.uuid4().hex,
                "--label", "org.domainforge.calibration=access-state", "--tmpfs", "/data", "-p", "127.0.0.1::6379", "--env-file", str(env),
                native.baseline()["redis_image"], "sh", "-c", 'exec redis-server --save "" --appendonly no --requirepass "$REDISCLI_AUTH"')
        self.addCleanup(self.release_redis)
        for attempt in range(60):
            result = subprocess.run(["docker", "--host", self.endpoint, "exec", self.redis_container, "redis-cli", "--no-auth-warning", "PING"], capture_output=True, text=True, timeout=5)
            if result.returncode == 0 and result.stdout.strip() == "PONG":
                break
            time.sleep(0.1)
        self.assertEqual(result.stdout.strip(), "PONG", "本轮 Redis 未就绪，不算访问反例")
        state = json.loads(self.docker("inspect", self.redis_container))[0]
        self.redis_credentials = {"container": self.redis_container, "port": int(state["NetworkSettings"]["Ports"]["6379/tcp"][0]["HostPort"]), "password": password}
        self.connections.write_text(json.dumps({"schema": 1, "postgres": self.credentials, "redis": self.redis_credentials}), encoding="utf-8")
        self.connections.chmod(0o600)
        native.connections(self.connections)

    def release_redis(self):
        inspected = json.loads(self.docker("inspect", self.redis_container))[0]
        self.assertEqual(inspected["Config"]["Labels"].get("org.domainforge.calibration"), "access-state")
        self.docker("rm", "-f", self.redis_container)

    def fixture(self):
        versions = json.loads((HERE / "maven-scope.lock.json").read_text())
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                   '<groupId>probe</groupId><artifactId>access-state</artifactId><version>1.0.0</version>'
                   '<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>' +
                   native.framework_pom() + '<build><sourceDirectory>src/test-named-production/java</sourceDirectory>'
                   '<testSourceDirectory>verification/java</testSourceDirectory><plugins>' + ''.join('<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-' +
                   name + '-plugin</artifactId><version>' + versions[name + '_version'] + '</version></plugin>' for name in ('resources', 'compiler')) + '</plugins></build></project>')
        for name in JAVA:
            self.write("src/test-named-production/java/probe/access/" + name, (HERE / name).read_text())
        self.write("src/main/resources/application.properties", '''gate.project=${DF_GATE_PROJECT}
spring.datasource.url=${DF_GATE_JDBC_URL}
spring.datasource.username=${DF_GATE_DB_USER}
spring.datasource.password=${DF_GATE_DB_PASSWORD}
spring.datasource.hikari.maximum-pool-size=4
spring.datasource.hikari.connection-timeout=1000
spring.data.redis.host=127.0.0.1
spring.data.redis.port=${DF_GATE_REDIS_PORT}
spring.data.redis.password=${DF_GATE_REDIS_PASSWORD}
spring.data.redis.database=${DF_GATE_REDIS_DATABASE}
spring.data.redis.timeout=300ms
spring.data.redis.connect-timeout=300ms
sa-token.token-name=Authorization
sa-token.token-prefix=Bearer
sa-token.is-read-header=true
sa-token.is-read-body=false
sa-token.is-read-cookie=false
sa-token.is-share=false
sa-token.timeout=${DF_GATE_TOKEN_TIMEOUT}
spring.jackson.default-property-inclusion=always
spring.main.banner-mode=off
logging.level.root=ERROR
''')

    def mutate(self, name, old, new):
        path = self.project / "src/test-named-production/java/probe/access" / name
        value = path.read_text()
        self.assertIn(old, value, "反例变异必须命中真实源码")
        path.write_text(value.replace(old, new))

    def mutate_method(self, name, method, old, new):
        path = self.project / "src/test-named-production/java/probe/access" / name
        source = path.read_text()
        match = re.search(r"\n  (?:public|private) [^\n]+ " + method + r"\(", source)
        self.assertIsNotNone(match, "反例必须找到实际方法")
        end = source.find("\n  /**", match.end())
        end = len(source) if end < 0 else end
        part = source[match.start():end]
        self.assertIn(old, part)
        path.write_text(source[:match.start()] + part.replace(old, new) + source[end:])

    def command(self, profile="authorization", diagnostic=None):
        native.connections(self.connections)
        self.sql("TRUNCATE gate_account,gate_resource,gate_audit;")
        for database in (0, 1):
            self.docker("exec", self.redis_container, "redis-cli", "--no-auth-warning", "-n", str(database), "FLUSHDB")
        self.state.write_text(json.dumps({"schema": 1, "application": "probe.access.AccessStateFixture", "profile": profile}))
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project), "--state", str(self.state), "--connections", str(self.connections)],
                                capture_output=True, text=True, timeout=45)
        self.assertIn(result.returncode, (0, 1), "须取得原生校准报告")
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, report.get("diagnostics"))
        if diagnostic:
            self.assertIn(diagnostic, [d["code"] for d in report["diagnostics"]], report)
        return report

    def repaired(self, profile):
        self.repair_count += 1
        self.project = self.root / ("access-repaired-" + str(self.repair_count))
        self.fixture()
        return self.command(profile)


class AccessResourceCommands(AccessStateFixtures):
    def test_real_resource_ownership_scope_permissions_and_conditional_write(self):
        report = self.command()
        self.assertTrue(report["real_redis_checked"])
        self.assertFalse(report["product_verified"])
        self.assertFalse(report["human_acceptance"])


class AccessScopeCommands(AccessStateFixtures):
    def test_list_scope_leak_is_rejected_despite_successful_http_and_valid_permission(self):
        self.mutate_method("AccessStateStore.java", "resources", "AND department=? ORDER BY id", "ORDER BY id")
        self.mutate_method("AccessStateStore.java", "resources", "actor.id(),\n        actor.scope()", "actor.id()")
        self.command(diagnostic="SG_ACCESS_STATE_RESOURCE")
        self.repaired("authorization")


class AccessOwnerCommands(AccessStateFixtures):
    def test_foreign_owned_resource_cannot_be_allowed_by_operation_permission(self):
        self.mutate_method("AccessStateStore.java", "resource", "owner=? AND", "")
        self.mutate_method("AccessStateStore.java", "resource", "actor.id(),", "")
        self.command(diagnostic="SG_ACCESS_STATE_BEHAVIOR")
        self.repaired("authorization")


class AccessOperationCommands(AccessStateFixtures):
    def test_read_permission_cannot_authorize_write(self):
        self.mutate("AccessStateFixture.java", '@SaCheckPermission("write")', '')
        self.command(diagnostic="SG_ACCESS_STATE_BEHAVIOR")
        self.repaired("authorization")


class AccessSessionCommands(AccessStateFixtures):
    def test_logout_must_remove_shared_real_session_then_repairs(self):
        self.mutate("AccessStateFixture.java", "StpUtil.logout();", "// deliberately omitted logout")
        self.command("session", "SG_ACCESS_STATE_BEHAVIOR")
        self.repaired("session")


class AccessExpirationCommands(AccessStateFixtures):
    def test_expiration_uses_real_redis_ttl(self):
        path = self.project / "src/main/resources/application.properties"
        path.write_text(path.read_text().replace("sa-token.timeout=${DF_GATE_TOKEN_TIMEOUT}", "sa-token.timeout=120"))
        self.command("expiration", "SG_ACCESS_STATE_SESSION")
        self.repaired("expiration")


class AccessChangesCommands(AccessStateFixtures):
    def test_disabled_account_cannot_continue_new_requests(self):
        self.mutate("AccessStateSecurity.java", "|| !account.active()", "")
        self.command("changes", "SG_ACCESS_STATE_BEHAVIOR")
        self.repaired("changes")


class AccessPermissionCommands(AccessStateFixtures):
    def test_stale_permissions_cannot_survive_reported_revocation(self):
        self.mutate("AccessStateSecurity.java", 'Arrays.asList(account.permissions().split(","))', 'List.of("admin", "read", "write")')
        self.command("changes", "SG_ACCESS_STATE_BEHAVIOR")
        self.repaired("changes")


class AccessRoleCommands(AccessStateFixtures):
    def test_stale_role_cannot_survive_reported_revocation(self):
        self.mutate("AccessStateSecurity.java", 'Arrays.asList(account.roles().split(","))', 'List.of("manager")')
        self.command("changes", "SG_ACCESS_STATE_BEHAVIOR")
        self.repaired("changes")


class AccessPasswordCommands(AccessStateFixtures):
    def test_saved_password_does_not_prove_old_sessions_invalid(self):
        self.mutate("AccessStateFixture.java", "StpUtil.logout(actor.id());", "// omitted revocation")
        self.mutate("AccessStateSecurity.java", "|| !Integer.valueOf(account.revision()).equals(revision)", "")
        self.command("password", "SG_ACCESS_STATE_BEHAVIOR")
        self.repaired("password")


class AccessFailuresCommands(AccessStateFixtures):
    def test_real_dependency_failures_restore_and_partial_password_cannot_report_complete(self):
        self.mutate("AccessStateFixture.java", "StpUtil.logout(actor.id());", "try { StpUtil.logout(actor.id()); } catch (org.springframework.dao.DataAccessException ignored) {}")
        self.command("failures", "SG_ACCESS_STATE_BEHAVIOR")
        self.repaired("failures")


class AccessThrottleCommands(AccessStateFixtures):
    def test_missing_enforcement_cannot_pass_real_login_shared_limit(self):
        self.mutate("AccessStateSecurity.java", "attempts > limit", "attempts > 1000")
        self.command("throttle", "SG_ACCESS_STATE_BEHAVIOR")
        self.repaired("throttle")


class AccessAuditCommands(AccessStateFixtures):
    def test_client_actor_cannot_impersonate_trusted_audit_identity(self):
        self.mutate("AccessStateFixture.java", "store.change(security.current().id(),", "store.change(input.actor(),")
        self.command("audit", "SG_ACCESS_STATE_AUDIT")
        self.repaired("audit")


class AccessAuditFailureCommands(AccessStateFixtures):
    def test_audit_failure_requires_independent_signal(self):
        self.mutate("AccessStateStore.java", 'System.err.println("GATE_AUDIT_FAILED:" + event);', '// deliberately missing alert')
        self.command("audit", "SG_ACCESS_STATE_AUDIT")
        self.repaired("audit")


class AccessReconciliationCommands(AccessStateFixtures):
    def test_partial_reconciliation_cannot_claim_complete_original_facts(self):
        self.mutate("AccessStateStore.java", '"manual-snapshot", result, true', '"manual-snapshot", result, false')
        self.command("audit", "SG_ACCESS_STATE_AUDIT")
        self.repaired("audit")


class AccessSecretCommands(AccessStateFixtures):
    def test_token_in_process_output_is_rejected(self):
        self.mutate("AccessStateSecurity.java", "return StpUtil.getTokenValue();", "System.err.println(StpUtil.getTokenValue()); return StpUtil.getTokenValue();")
        self.command(diagnostic="SG_ACCESS_STATE_SECRET")
        self.repaired("authorization")


class AccessRedisCommands(AccessStateFixtures):
    def test_memory_dao_cannot_impersonate_redis_adapter(self):
        self.mutate("AccessStateFixture.java", "public class AccessStateFixture {", "public class AccessStateFixture { @Bean @org.springframework.context.annotation.Primary cn.dev33.satoken.dao.SaTokenDao memory() { return new cn.dev33.satoken.dao.SaTokenDaoDefaultImpl(); }")
        self.command("session", "SG_ACCESS_STATE_RESOURCE")
        self.repaired("session")


class AccessProjectCommands(AccessStateFixtures):
    def test_shared_physical_redis_cannot_merge_project_sessions(self):
        path = self.project / "src/main/resources/application.properties"
        path.write_text(path.read_text().replace("spring.data.redis.database=${DF_GATE_REDIS_DATABASE}", "spring.data.redis.database=0"))
        self.command("session", "SG_ACCESS_STATE_RESOURCE")
        self.repaired("session")


class AccessInputChecks(unittest.TestCase):
    def test_contract_and_missing_environment_cannot_self_certify(self):
        import calibrate_access_state as gate
        import access_state_environment as native
        from calibrate_source import ProbeFailure
        source = {"schema": 1, "application": "probe.access.AccessStateFixture", "profile": "session"}
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "input.json"
            for changed in (dict(source, expected="anything"), dict(source, profile=[]), dict(source, profile="unknown"), dict(source, schema=True), dict(source, application="../elsewhere")):
                path.write_text(json.dumps(changed))
                with self.assertRaises(ProbeFailure) as failure:
                    gate.declaration(path)
                self.assertEqual(failure.exception.diagnostic["code"], "SG_ACCESS_STATE_INPUT")
            path.write_text(json.dumps(source))
            self.assertEqual(gate.declaration(path), source)
        with self.assertRaises(ProbeFailure) as failure:
            native.environment(types.SimpleNamespace(access_state_tools=None))
        self.assertEqual(failure.exception.diagnostic["code"], "SG_ACCESS_STATE_TOOLS")

    def test_received_audit_identifier_cannot_inject_observer_sql(self):
        from access_state_scenarios import audit_row
        from calibrate_source import ProbeFailure
        for event in ("' OR true--", "not-an-event", None):
            with self.assertRaises(ProbeFailure) as failure:
                audit_row(None, event)
            self.assertEqual(failure.exception.diagnostic["code"], "SG_ACCESS_STATE_AUDIT")

    def test_public_or_linked_connections_are_rejected_before_docker(self):
        import access_state_environment as native
        from calibrate_source import ProbeFailure
        with tempfile.TemporaryDirectory() as directory:
            target, link = pathlib.Path(directory) / "connection.json", pathlib.Path(directory) / "link.json"
            target.write_text("{}")
            target.chmod(0o644)
            link.symlink_to(target)
            for path in (target, link):
                with self.assertRaises(ProbeFailure) as failure:
                    native.connections(path)
                self.assertEqual(failure.exception.diagnostic["code"], "SG_ACCESS_STATE_CONNECTION")


class AccessToolQualityChecks(unittest.TestCase):
    def test_observer_and_fixture_source_quality(self):
        tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(tools)
        files = [str(HERE / name) for name in (*JAVA, "AccessStateInventory.java")]
        for action in ("comments", "method-presence", "format", "complexity"):
            argv = [sys.executable, "-B", str(HERE / "calibrate_source.py"), "--tools", tools, action]
            if action in ("comments", "complexity"):
                argv += ["--purpose", "production"]
            result = subprocess.run([*argv, *files], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(set(json.loads(result.stdout)["scanned"]), set(files))


class AccessLoginSecretCommands(AccessStateFixtures):
    def test_login_delivery_exempts_only_the_token_field(self):
        self.mutate("AccessStateFixture.java", 'return ok(Map.of("token", security.login(input.id(), input.password())));',
                    'return new Envelope("SUCCESS", input.password(), Map.of("token", security.login(input.id(), input.password())));')
        self.command(diagnostic="SG_ACCESS_STATE_SECRET")
        self.repaired("authorization")


class AccessHeaderSecretCommands(AccessStateFixtures):
    def test_http_headers_cannot_disclose_login_material(self):
        self.mutate("AccessStateFixture.java", 'return ok(Map.of("token", security.login(input.id(), input.password())));',
                    '((org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getResponse().setHeader("X-Debug", input.password()); return ok(Map.of("token", security.login(input.id(), input.password())));')
        self.command(diagnostic="SG_ACCESS_STATE_SECRET")
        self.repaired("authorization")


class AccessNewHashSecretCommands(AccessStateFixtures):
    def test_new_hash_is_checked_against_the_already_received_response(self):
        self.mutate("AccessStateFixture.java", 'return ok(store.audit(actor.id(), actor.id(), "password", "changed", false));',
                    'return new Envelope("SUCCESS", store.account(actor.id()).hash(), store.audit(actor.id(), actor.id(), "password", "changed", false));')
        self.command("password", "SG_ACCESS_STATE_SECRET")
        self.repaired("password")


class AccessFailedLoginSecretCommands(AccessStateFixtures):
    def test_failed_login_does_not_echo_the_actual_rejected_password(self):
        self.mutate("AccessStateFixture.java", 'new Envelope(code, "失败", null)', 'new Envelope(code, "wrong", null)')
        self.command("throttle", "SG_ACCESS_STATE_SECRET")
        self.repaired("throttle")

ENTRY_GROUPS = [
    ('access-state-login-secret-tests', ['AccessLoginSecretCommands']),
    ('access-state-header-secret-tests', ['AccessHeaderSecretCommands']),
    ('access-state-new-hash-secret-tests', ['AccessNewHashSecretCommands']),
    ('access-state-failed-login-secret-tests', ['AccessFailedLoginSecretCommands']),

    ('access-gate', ['AccessResourceCommands']),
    ('access-state-scope-tests', ['AccessScopeCommands']),
    ('access-state-owner-tests', ['AccessOwnerCommands']),
    ('access-state-operation-tests', ['AccessOperationCommands']),
    ('access-state-session-tests', ['AccessSessionCommands']),
    ('access-state-expiration-tests', ['AccessExpirationCommands']),
    ('access-state-changes-tests', ['AccessChangesCommands']),
    ('access-state-permission-tests', ['AccessPermissionCommands']),
    ('access-state-role-tests', ['AccessRoleCommands']),
    ('access-state-password-tests', ['AccessPasswordCommands']),
    ('access-state-failures-tests', ['AccessFailuresCommands']),
    ('access-state-throttle-tests', ['AccessThrottleCommands']),
    ('access-state-audit-tests', ['AccessAuditCommands']),
    ('access-state-audit-failure-tests', ['AccessAuditFailureCommands']),
    ('access-state-reconciliation-tests', ['AccessReconciliationCommands']),
    ('access-state-secret-tests', ['AccessSecretCommands']),
    ('access-state-redis-tests', ['AccessRedisCommands']),
    ('access-state-project-tests', ['AccessProjectCommands']),
    ('access-state-input-tests', ['AccessInputChecks', 'AccessToolQualityChecks', 'AccessStatePartitionChecks']),
]


class AccessStatePartitionChecks(unittest.TestCase):
    def test_all_access_state_cases_registered_once(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text())
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text()
        loader, module = unittest.TestLoader(), sys.modules[__name__]
        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [name for child in suite for name in identifiers(child)]
        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "访问状态模块不得重复加载用例")
        selected = set()
        for check, names in ENTRY_GROUPS:
            self.assertIn(check, catalog["checks"], "访问状态分组未登记")
            definition = catalog["checks"][check]
            argv = ["@python", "-B", "tools/engineering_gate/test_access_state.py", *names]
            self.assertEqual(definition["argv"], argv, "访问状态命令偏离登记")
            self.assertEqual(definition["environment_argv"], ["@python", "-B", "tools/engineering_gate/calibrate_access_state.py", "--environment"], "访问状态环境入口偏离")
            self.assertTrue(definition["requires_test_count"])
            self.assertIn(check, catalog["maintenance"]["checks"])
            self.assertEqual(workflow.splitlines().count("        run: python " + " ".join(argv[1:])), 1, "访问状态 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group)
            self.assertEqual(len(group), len(set(group)))
            self.assertFalse(selected & set(group), "访问状态跨组用例重复")
            selected.update(group)
        self.assertEqual(selected, set(complete), "访问状态分组遗漏用例")


if __name__ == "__main__":
    unittest.main()
