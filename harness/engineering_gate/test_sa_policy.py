"""原生注解有效策略与真实 HTTP 的隔离反例；不实现产品 IAM 或权限中心。"""

import json
import os
import pathlib
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET

import test_sa_access


HERE = pathlib.Path(__file__).resolve().parent


class PolicyFixtures(test_sa_access.SaAccessFixtures):
    def setUp(self):
        super().setUp()
        self.policy_path = self.root / "policies.json"
        self.policy_fixture()

    def policy_fixture(self, merged=True, guard=True):
        self.access_fixture()
        app = self.project / "src/test-named-production/java/probe/http/App.java"
        source = app.read_text(encoding="utf-8").replace(
            'new cn.dev33.satoken.interceptor.SaInterceptor()).addPathPatterns("/probe/session")',
            'new cn.dev33.satoken.interceptor.SaInterceptor(handler -> PolicyConfig.requireDeclared(handler))).addPathPatterns("/**")'
            if guard else 'new cn.dev33.satoken.interceptor.SaInterceptor()).addPathPatterns("/**")')
        source = source.replace('return args -> cn.dev33.satoken.stp.StpUtil.createLoginSession("probe-reader",',
            '''return args -> {
      ''' + ('''cn.dev33.satoken.strategy.SaAnnotationStrategy.instance.getAnnotation =
        (element, kind) -> org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(element, kind);
      ''' if merged else "") + '''cn.dev33.satoken.stp.StpUtil.createLoginSession("probe-guest",
        new cn.dev33.satoken.stp.parameter.SaLoginParameter().setToken("synthetic-guest-token"));
      cn.dev33.satoken.stp.StpUtil.createLoginSession("probe-reader",''').replace(
            '.setToken("synthetic-access-token"));', '.setToken("synthetic-access-token")); };')
        app.write_text(source, encoding="utf-8")
        controller = self.project / "src/test-named-production/java/probe/http/Controller.java"
        controller.write_text(controller.read_text(encoding="utf-8").replace(
            '@org.springframework.web.bind.annotation.RestController', '@cn.dev33.satoken.annotation.SaIgnore\n@org.springframework.web.bind.annotation.RestController'), encoding="utf-8")
        self.write("src/test-named-production/java/probe/http/PolicyConfig.java", '''package probe.http;
/** 被测应用自己的统一接入，观察器不得注入或修复此逻辑。 */
@org.springframework.context.annotation.Configuration
public class PolicyConfig implements cn.dev33.satoken.stp.StpInterface {
  /** 合成账号权限由原生框架实际读取，不作为产品权限数据。 */
  public java.util.List<String> getPermissionList(Object id, String type) {
    return id.equals("probe-reader") ? java.util.List.of("order:read") : java.util.List.of();
  }
  /** 本夹具不使用角色替代操作权限。 */
  public java.util.List<String> getRoleList(Object id, String type) { return java.util.List.of(); }
  /** 独立的应用默认拒绝；只在业务处理器上执行，不修改框架生成端点。 */
  static void requireDeclared(Object handler) {
    if (!(handler instanceof org.springframework.web.method.HandlerMethod target)
        || !target.getBeanType().getPackageName().equals("probe.http")) { return; }
    var strategy = cn.dev33.satoken.strategy.SaAnnotationStrategy.instance;
    var method = target.getMethod();
    if (strategy.isAnnotationPresent.apply(method, cn.dev33.satoken.annotation.SaCheckLogin.class)) { return; }
    for (var element : java.util.List.of(method.getDeclaringClass(), method)) {
      var at = (cn.dev33.satoken.annotation.SaCheckPermission) strategy.getAnnotation.apply(element, cn.dev33.satoken.annotation.SaCheckPermission.class);
      if (at != null && at.value().length > 0 && java.util.Arrays.stream(at.value()).allMatch(code -> !code.isBlank())) { return; }
    }
    throw new cn.dev33.satoken.exception.NotPermissionException("undeclared-policy");
  }
}
''')
        self.write("src/test-named-production/java/probe/http/Reader.java", '''package probe.http;
/** 通过框架公开解析扩展与 Spring 成熟合并解析支持组合声明。 */
@cn.dev33.satoken.annotation.SaCheckPermission("order:read")
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD})
public @interface Reader {}
''')
        self.write("src/test-named-production/java/probe/http/PolicyBase.java", '''package probe.http;
/** 继承实际映射方法，不把子类名称误当作声明来源。 */
public class PolicyBase {
  /** 由原生框架执行继承方法上的身份校验。 */
  @cn.dev33.satoken.annotation.SaCheckLogin
  @org.springframework.web.bind.annotation.GetMapping(value="/probe/inherited", produces="application/json")
  public Envelope inherited() { return new Envelope("SUCCESS", "完成", new Payload("ok")); }
}
''')
        self.write("src/test-named-production/java/probe/http/PolicyController.java", '''package probe.http;
/** 类级登录和方法级权限共同生效的真实注册入口。 */
@cn.dev33.satoken.annotation.SaCheckLogin
@org.springframework.web.bind.annotation.RestController
public class PolicyController extends PolicyBase {
  /** 显式匿名遵循原生 SaIgnore 优先语义。 */
  @cn.dev33.satoken.annotation.SaIgnore
  @org.springframework.web.bind.annotation.GetMapping(value="/probe/public", produces="application/json")
  public Envelope anonymous() { return new Envelope("SUCCESS", "完成", new Payload("ok")); }
  /** 类级与方法级条件不能用最后一个注解覆盖。 */
  @cn.dev33.satoken.annotation.SaCheckPermission("order:read")
  @org.springframework.web.bind.annotation.GetMapping(value="/probe/permission", produces="application/json")
  public Envelope permission() { return new Envelope("SUCCESS", "完成", new Payload("ok")); }
  /** 组合权限声明仍须真实参与原生校验。 */
  @Reader
  @org.springframework.web.bind.annotation.GetMapping(value="/probe/composed", produces="application/json")
  public Envelope composed() { return new Envelope("SUCCESS", "完成", new Payload("ok")); }
}
''')
        errors = self.project / "src/test-named-production/java/probe/http/SessionErrors.java"
        errors.write_text(errors.read_text(encoding="utf-8").replace("public class SessionErrors {", '''public class SessionErrors {
  /** 未授权与未登录不能混成同一结果。 */
  @org.springframework.web.bind.annotation.ExceptionHandler(cn.dev33.satoken.exception.NotPermissionException.class)
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.FORBIDDEN)
  public Envelope forbidden() { return new Envelope("FORBIDDEN", "无权限", null); }
'''), encoding="utf-8")
        self.policy_definition = {"schema": 1, "entries": []}
        for endpoint in self.definition["endpoints"]:
            kind = "login" if endpoint["path"] == "/probe/session" else "anonymous"
            cases = {"allow": "bearer" if endpoint["method"] == "GET" else "post-bearer", "anonymous": "absent"} if kind == "login" else {"allow": endpoint["scenarios"][0]["id"]}
            if endpoint["path"] == "/probe/session" and endpoint["method"] == "POST":
                absent = self.access_case("post-absent", method="POST")
                endpoint["scenarios"].append(absent)
                cases["anonymous"] = "post-absent"
            self.policy_entry(endpoint, kind, cases)
        login = next(item for item in self.definition["endpoints"] if item["path"] == "/probe/session" and item["method"] == "GET")
        guest = self.access_case("guest-login", headers={"Authorization": "Bearer synthetic-guest-token"}, allow=True)
        login["scenarios"].append(guest)
        for path, kind in (("public", "anonymous"), ("inherited", "login"), ("permission", "permission"), ("composed", "permission")):
            roles = ["allow"] if kind == "anonymous" else ["allow", "anonymous"] + (["forbidden"] if kind == "permission" else [])
            cases = []
            for role in roles:
                allowed = role == "allow"
                status = 200 if allowed else 401 if role == "anonymous" else 403
                headers = {} if role == "anonymous" or kind == "anonymous" else {"Authorization": "Bearer " + (self.token if allowed else "synthetic-guest-token")}
                cases.append({"id": path + "-" + role, "request": {"path": "/probe/" + path, "method": "GET", "headers": headers, "body": None},
                              "status": status, "code": "SUCCESS" if allowed else "NOT_LOGGED_IN" if status == 401 else "FORBIDDEN", "data": {"value": "ok"} if allowed else None})
            endpoint = {"path": "/probe/" + path, "method": "GET", "protocol": "json", "scenarios": cases}
            self.definition["endpoints"].append(endpoint)
            self.policy_entry(endpoint, kind, {role: path + "-" + role for role in roles}, "guest-login" if kind == "permission" else None)

    def policy_entry(self, endpoint, kind, cases, witness=None):
        conditions = [] if kind == "anonymous" else [{"kind": "login", "type": ""}]
        if kind == "permission":
            conditions.append({"kind": "permission", "type": "", "mode": "AND", "values": ["order:read"], "or_roles": []})
        self.policy_definition["entries"].append({"path": endpoint["path"], "method": endpoint["method"], "kind": kind,
                                                  "conditions": conditions, "cases": cases, "login_witness": witness})

    def command(self, diagnostic=None):
        self.policy_path.write_text(json.dumps(self.policy_definition), encoding="utf-8")
        return super().command(diagnostic)

    def repaired_policy(self):
        self.repaired_project()
        self.policy_fixture()


class PolicyBehaviorCommands(PolicyFixtures):
    def test_probe_does_not_change_original_component_assembly(self):
        app = self.project / "src/test-named-production/java/probe/http/App.java"
        app.write_text(app.read_text(encoding="utf-8").replace("  /** 从唯一代码来源", '''  /** 原始装配不得出现观察工具的临时类型。 */
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner originalAssembly(org.springframework.context.ApplicationContext context) {
    return args -> {
      int count = context.getBeanNamesForType(Class.forName("probe.http.DomainForgeUnassignedProbe")).length;
      if (count != 0) { throw new IllegalStateException("Tool probe polluted original component assembly: " + count); }
    };
  }
  /** 从唯一代码来源'''), encoding="utf-8")
        self.assertTrue(self.command()["default_deny_checked"])

    def test_missing_guard_cannot_pass_even_with_all_current_entries_declared(self):
        self.policy_fixture(guard=False)
        self.command("SG_ACCESS_DEFAULT_DENY")
        self.repaired_policy()
        self.command()

    def test_actual_anonymous_login_class_inheritance_and_composed_permission(self):
        report = self.command()
        self.assertTrue(report.get("annotation_policy_checked"), "真实原生有效策略未核对，不能仅凭 HTTP 结构报通过")
        self.assertTrue(report["default_deny_checked"])
        self.assertEqual(len(report["effective_policies"]), len(self.definition["endpoints"]))
        self.assertFalse(report["product_verified"])
        self.assertFalse(report["redis_verified"])
        self.assertNotIn("synthetic-guest-token", json.dumps(report))

    def test_native_or_permissions_preserve_login_and_forbidden_scenarios(self):
        controller = self.project / "src/test-named-production/java/probe/http/PolicyController.java"
        controller.write_text(controller.read_text(encoding="utf-8").replace(
            '@cn.dev33.satoken.annotation.SaCheckPermission("order:read")',
            '@cn.dev33.satoken.annotation.SaCheckPermission(value={"order:write", "order:read"}, mode=cn.dev33.satoken.annotation.SaMode.OR)'), encoding="utf-8")
        entry = next(item for item in self.policy_definition["entries"] if item["path"] == "/probe/permission")
        entry["conditions"][1].update(mode="OR", values=["order:read", "order:write"])
        self.assertTrue(self.command()["annotation_policy_checked"])


class PolicyCoverageCommands(PolicyFixtures):
    def test_missing_declaration_is_rejected_and_actual_guard_denies_logged_in_too(self):
        controller = self.project / "src/test-named-production/java/probe/http/SessionController.java"
        controller.write_text(controller.read_text(encoding="utf-8").replace("@cn.dev33.satoken.annotation.SaCheckLogin", ""), encoding="utf-8")
        report = self.command("SG_ACCESS_POLICY_MISSING")
        actual = {item["scenario"]: item["status"] for item in report["interactions"]}
        self.assertEqual(actual["absent"], 403)
        self.assertEqual(actual["bearer"], 403)
        self.repaired_policy()
        self.command()

    def test_empty_permission_cannot_be_relabelled_as_covered_login(self):
        controller = self.project / "src/test-named-production/java/probe/http/PolicyController.java"
        controller.write_text(controller.read_text(encoding="utf-8").replace(
            '@cn.dev33.satoken.annotation.SaCheckPermission("order:read")',
            '@cn.dev33.satoken.annotation.SaCheckPermission'), encoding="utf-8")
        report = self.command("SG_ACCESS_POLICY_EMPTY")
        self.assertEqual(next(item["status"] for item in report["interactions"] if item["scenario"] == "permission-forbidden"), 200)
        self.repaired_policy()
        self.command()


    def test_missing_default_guard_cannot_hide_undeclared_success(self):
        self.policy_fixture(guard=False)
        controller = self.project / "src/test-named-production/java/probe/http/SessionController.java"
        controller.write_text(controller.read_text(encoding="utf-8").replace("@cn.dev33.satoken.annotation.SaCheckLogin", ""), encoding="utf-8")
        report = self.command("SG_ACCESS_DEFAULT_DENY")
        self.assertEqual(next(item["status"] for item in report["interactions"] if item["scenario"] == "absent"), 200)
        self.repaired_policy()
        self.command()


class PolicyExecutionCommands(PolicyFixtures):
    def test_declared_annotations_without_actual_interception_cannot_pass(self):
        app = self.project / "src/test-named-production/java/probe/http/App.java"
        app.write_text(app.read_text(encoding="utf-8").replace('addPathPatterns("/**")', 'addPathPatterns("/never-matches")'), encoding="utf-8")
        report = self.command("SG_HTTP_SEMANTICS")
        self.assertFalse(report["annotation_policy_checked"])
        self.assertEqual(next(item["status"] for item in report["interactions"] if item["scenario"] == "permission-forbidden"), 200)
        self.repaired_policy()
        self.command()

    def test_composition_without_actual_framework_resolution_is_not_assumed(self):
        self.policy_fixture(merged=False)
        report = self.command("SG_ACCESS_POLICY_DRIFT")
        self.assertEqual(next(item["status"] for item in report["interactions"] if item["scenario"] == "composed-forbidden"), 200)
        self.repaired_policy()
        self.command()

    def test_replaced_native_permission_handler_is_not_certified_by_annotation_presence(self):
        app = self.project / "src/test-named-production/java/probe/http/App.java"
        app.write_text(app.read_text(encoding="utf-8").replace('return args -> {', '''return args -> {
      cn.dev33.satoken.strategy.SaAnnotationStrategy.instance.annotationHandlerMap.put(
        cn.dev33.satoken.annotation.SaCheckPermission.class,
        new cn.dev33.satoken.annotation.handler.SaCheckPermissionHandler() {
          public void checkMethod(cn.dev33.satoken.annotation.SaCheckPermission at, java.lang.reflect.AnnotatedElement element) {}
        });'''), encoding="utf-8")
        report = self.command("SG_ACCESS_POLICY_SCOPE")
        self.assertEqual(next(item["status"] for item in report["interactions"] if item["scenario"] == "permission-forbidden"), 200)
        self.repaired_policy()
        self.command()


class PolicyInputCommands(PolicyFixtures):
    def test_required_rejection_roles_or_identity_witness_cannot_be_omitted_or_relabelled(self):
        original = json.loads(json.dumps(self.policy_definition))
        for change in ("missing", "duplicate", "wrong-route", "wrong-witness", "wrong-subject"):
            with self.subTest(change=change):
                self.policy_definition = json.loads(json.dumps(original))
                entry = next(item for item in self.policy_definition["entries"] if item["path"] == "/probe/permission")
                if change == "missing":
                    del entry["cases"]["forbidden"]
                elif change == "duplicate":
                    entry["cases"]["forbidden"] = entry["cases"]["anonymous"]
                elif change == "wrong-route":
                    entry["cases"]["forbidden"] = "composed-forbidden"
                elif change == "wrong-witness":
                    entry["login_witness"] = "not-run"
                else:
                    entry["login_witness"] = "bearer"
                self.command("SG_ACCESS_POLICY_SCENARIOS")
                self.assertFalse((self.project / "target").exists())
        self.policy_definition = original
        self.command()

    def test_omitted_registered_policy_cannot_shrink_coverage(self):
        self.policy_definition["entries"] = [item for item in self.policy_definition["entries"] if item["path"] != "/probe/permission"]
        report = self.command("SG_ACCESS_POLICY_COVERAGE")
        self.assertEqual(len(report["effective_policies"]), len(self.definition["endpoints"]))
        self.repaired_policy()
        self.command()

    def test_independent_intended_permission_cannot_drift_with_valid_runtime_responses(self):
        entry = next(item for item in self.policy_definition["entries"] if item["path"] == "/probe/permission")
        entry["conditions"][1]["values"] = ["order:write"]
        report = self.command("SG_ACCESS_POLICY_DRIFT")
        self.assertEqual(next(item["status"] for item in report["interactions"] if item["scenario"] == "permission-allow"), 200)
        self.repaired_policy()
        self.command()


class PolicyToolQualityChecks(unittest.TestCase):
    def test_default_deny_requires_nonempty_dispatched_identity(self):
        import sa_policy_contracts
        from calibrate_source import ProbeFailure

        original = ET.fromstring('''<observation><default-deny-probe path="/tool-probe" owner="probe.http.Tool" handler="unassigned">
          <response scenario="__default_deny_anonymous" status="403" dispatched-pattern="/tool-probe" dispatched-owner="probe.http.Tool" dispatched-handler="unassigned"/>
          <response scenario="__default_deny_authenticated" status="403" dispatched-pattern="/tool-probe" dispatched-owner="probe.http.Tool" dispatched-handler="unassigned"/>
        </default-deny-probe></observation>''')
        self.assertEqual(sa_policy_contracts.default_deny(original), [])
        for field, observed in (("path", "dispatched-pattern"), ("owner", "dispatched-owner"), ("handler", "dispatched-handler")):
            for value in (None, "", " "):
                with self.subTest(field=field, value=value):
                    document = ET.fromstring(ET.tostring(original))
                    for node, name in [(document[0], field), *[(item, observed) for item in document[0]]]:
                        if value is None:
                            del node.attrib[name]
                        else:
                            node.set(name, value)
                    with self.assertRaises(ProbeFailure) as error:
                        sa_policy_contracts.default_deny(document)
                    self.assertEqual(error.exception.diagnostic["code"], "SG_ACCESS_DEFAULT_DENY_OBSERVATION")
        for status in ("200", "404"):
            document = ET.fromstring(ET.tostring(original))
            document[0][0].set("status", status)
            self.assertEqual(sa_policy_contracts.default_deny(document), [{"code": "SG_ACCESS_DEFAULT_DENY"}])

    def test_policy_observer_keeps_owned_source_quality(self):
        tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(tools, "策略观察器质量必须使用原生工具")
        sources = [str(HERE / name) for name in ("SaAccessInventory.java", "DomainForgeUnassignedProbe.java")]
        for action in ("comments", "method-presence", "format", "complexity"):
            argv = [sys.executable, "-B", str(HERE / "calibrate_source.py"), "--tools", tools, action]
            if action in ("comments", "complexity"):
                argv.extend(["--purpose", "production"])
            result = subprocess.run([*argv, *sources], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class PolicyPartitionChecks(unittest.TestCase):
    def test_fixed_policy_groups_cover_every_case_once_and_match_ci(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module, loader = sys.modules[__name__], unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "策略模块不得重复加载用例")
        selected = set()
        for check, names in (("policy-behavior-tests", ["PolicyBehaviorCommands", "PolicyToolQualityChecks", "PolicyPartitionChecks"]),
                             ("policy-coverage-tests", ["PolicyCoverageCommands"]),
                             ("policy-execution-tests", ["PolicyExecutionCommands"]),
                             ("policy-input-tests", ["PolicyInputCommands"])):
            self.assertIn(check, catalog["checks"], "策略校准分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "harness/engineering_gate/test_sa_policy.py", *names], "策略分组命令偏离登记")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"], ["@python", "-B", "harness/engineering_gate/calibrate_access.py", "--environment"], "策略环境预检偏离固定入口")
            self.assertEqual(workflow.splitlines().count("        run: python -B harness/engineering_gate/test_sa_policy.py " + " ".join(names)), 1, "策略 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertEqual(len(group), len(set(group)), "策略组内用例重复")
            self.assertTrue(group)
            self.assertFalse(selected & set(group), "策略跨组用例重复")
            selected.update(group)
        self.assertEqual(selected, set(complete), "策略分组遗漏用例")

if __name__ == "__main__":
    unittest.main()
