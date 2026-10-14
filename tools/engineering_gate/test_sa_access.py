"""真实 Sa-Token 取令牌与拦截机制；临时内存会话不能冒充 Redis 或产品 IAM。"""

import json
import os
import pathlib
import subprocess
import sys
import unittest
import urllib.parse

import test_http_contract


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_access.py"


class SaAccessFixtures(test_http_contract.HttpContractFixtures):
    def setUp(self):
        super().setUp()
        self.access_tools = os.environ.get("DOMAIN_FORGE_GATE_ACCESS_TOOLS", "")
        self.access = self.root / "access.json"
        self.access_fixture()

    def access_fixture(self, properties=""):
        self.fixture()
        self.token = "synthetic-access-token"
        pom = self.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("</dependencies>\n<build>",
                       '<dependency><groupId>cn.dev33</groupId><artifactId>sa-token-spring-boot4-starter</artifactId>'
                       '<version>1.46.0</version></dependency></dependencies>\n<build>'), encoding="utf-8")
        self.write("src/main/resources/application.properties", '''sa-token.token-name=Authorization
sa-token.token-prefix=Bearer
sa-token.is-read-header=true
sa-token.is-read-body=false
sa-token.is-read-cookie=false
sa-token.is-print=false
sa-token.data-refresh-period=1
''' + properties)
        app = self.project / "src/test-named-production/java/probe/http/App.java"
        app.write_text(app.read_text(encoding="utf-8").replace("public class App {",
                       "public class App implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {")
                       .replace("  /** 从唯一代码来源", '''  /** 注册原生拦截器，工具不得替应用自动补注册。 */
  @Override
  public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
    registry.addInterceptor(new cn.dev33.satoken.interceptor.SaInterceptor()).addPathPatterns("/probe/session");
  }
  /** 创建只用于本进程校准的原生会话，不是管理员或产品初始化。 */
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner session() {
    return args -> cn.dev33.satoken.stp.StpUtil.createLoginSession("probe-reader",
      new cn.dev33.satoken.stp.parameter.SaLoginParameter().setToken("synthetic-access-token"));
  }
  /** 从唯一代码来源'''), encoding="utf-8")
        self.write("src/test-named-production/java/probe/http/SessionController.java", '''package probe.http;
/** 原生登录检查的隔离 HTTP 入口，不实现密码认证。 */
@org.springframework.web.bind.annotation.RestController
public class SessionController {
  /** 验证原生登录检查，返回值不代替拦截器的拒绝责任。 */
  @cn.dev33.satoken.annotation.SaCheckLogin
  @org.springframework.web.bind.annotation.RequestMapping(value = "/probe/session",
    method = {org.springframework.web.bind.annotation.RequestMethod.GET, org.springframework.web.bind.annotation.RequestMethod.POST}, produces = "application/json")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未认证",
    content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = Envelope.class)))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完成",
    content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = Envelope.class)))
  public Envelope session() { return new Envelope("SUCCESS", "完成", new Payload("ok")); }
}
''')
        self.write("src/test-named-production/java/probe/http/SessionErrors.java", '''package probe.http;
/** 保留真实未认证 HTTP 状态和三字段外壳，不能用全部 HTTP 200 掩盖拒绝。 */
@org.springframework.web.bind.annotation.RestControllerAdvice
public class SessionErrors {
  /** 将本夹具实际未登录异常映射为未认证，不声明产品错误码集合。 */
  @org.springframework.web.bind.annotation.ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
  public Envelope unauthenticated() { return new Envelope("NOT_LOGGED_IN", "未认证", null); }
}
''')
        get_cases = [self.access_case("bearer", headers={"Authorization": "Bearer " + self.token}, allow=True),
                     self.access_case("absent"), self.access_case("wrong-prefix", headers={"Authorization": self.token}),
                     self.access_case("invalid", headers={"Authorization": "Bearer synthetic-invalid-token"}),
                     self.access_case("query", query={"Authorization": "Bearer " + self.token}),
                     self.access_case("cookie", headers={"Cookie": "Authorization=" + self.token})]
        post_cases = [self.access_case("form", method="POST", headers={"Content-Type": "application/x-www-form-urlencoded"},
                                       body=urllib.parse.urlencode({"Authorization": "Bearer " + self.token})),
                      self.access_case("post-bearer", method="POST", headers={"Authorization": "Bearer " + self.token}, allow=True)]
        # 此接口不接受表单；只在非法凭证反例发送它。响应、401 和身份拒绝始终校验。
        post_cases[0]["valid_request"] = False
        for method, cases in (("GET", get_cases), ("POST", post_cases)):
            self.definition["endpoints"].append({"path": "/probe/session", "method": method, "protocol": "json", "scenarios": cases})
        self.access_definition = {"schema": 1, "transport": {role: role for role in
                                  ("bearer", "absent", "wrong-prefix", "invalid", "query", "form", "cookie")}}

    def access_case(self, identifier, method="GET", headers=None, query=None, body=None, allow=False):
        path = "/probe/session" + ("?" + urllib.parse.urlencode(query) if query else "")
        return {"id": identifier, "request": {"path": path, "method": method, "headers": headers or {}, "body": body},
                "status": 200 if allow else 401, "code": "SUCCESS" if allow else "NOT_LOGGED_IN", "data": {"value": "ok"} if allow else None}

    def command(self, diagnostic=None, entry=ENTRY):
        self.contract.write_text(json.dumps(self.definition), encoding="utf-8")
        self.access.write_text(json.dumps(self.access_definition), encoding="utf-8")
        argv = [sys.executable, "-B", str(entry), "--project", str(self.project), "--scenarios", str(self.contract),
                "--baseline", str(self.released), "--baseline-sha256", self.released_sha, "--http-tools", self.http_tools,
                "--maven-home", self.maven, "--repository", self.repository]
        if entry == ENTRY:
            argv.extend(["--access", str(self.access), "--access-tools", self.access_tools])
            if getattr(self, "policy_path", None):
                argv.extend(["--policies", str(self.policy_path)])
        result = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        self.assertIn(result.returncode, (0, 1), result.stdout + result.stderr)
        self.assertTrue(result.stdout.strip(), result.stderr)
        report = json.loads(result.stdout)
        logs = "\n".join(path.name + "\n" + path.read_text(encoding="utf-8")[-3500:] for path in
                         sorted((self.project / "target").glob("http-*.log")))
        detail = json.dumps({key: report.get(key) for key in ("ok", "diagnostics", "runtime_observation")}, ensure_ascii=False) + "\n" + logs
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, detail)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], detail)
        return report


class AccessTransportCommands(SaAccessFixtures):
    def test_actual_bearer_allows_and_non_header_channels_do_not_authenticate(self):
        report = self.command()
        self.assertEqual(report["proof_scope"], "isolated-sa-token-header-transport-only")
        self.assertTrue(report["runtime_schema_checked"])
        self.assertEqual(len(report["access_scenarios"]["roles"]), 7)
        self.assertEqual(report["runtime_observation"]["sa-dao"], "cn.dev33.satoken.dao.SaTokenDaoDefaultImpl")
        for field in ("product_verified", "access_policy_verified", "redis_verified", "human_acceptance"):
            self.assertFalse(report[field])
        observed = {case["scenario"]: case["status"] for case in report["interactions"]}
        self.assertEqual(observed["bearer"], 200)
        self.assertEqual(observed["post-bearer"], 200)
        self.assertTrue(all(observed[role] == 401 for role in report["access_scenarios"]["roles"] if role != "bearer"))
        for value in (self.token, "synthetic-invalid-token"):
            self.assertFalse(value in json.dumps(report), "公开校准报告不能复制令牌、Cookie 或表单凭证")

    def test_missing_native_prefix_is_rejected_and_raw_token_really_authenticates(self):
        self.access_fixture("sa-token.token-prefix=")
        report = self.command("SG_ACCESS_TRANSPORT")
        self.assertEqual(next(case["status"] for case in report["interactions"] if case["scenario"] == "wrong-prefix"), 200)
        self.repaired_project()
        self.access_fixture()
        self.command()


class AccessConfigurationCommands(SaAccessFixtures):
    def test_request_parameter_reading_is_rejected_and_native_http_really_accepts_it(self):
        self.access_fixture("sa-token.is-read-body=true")
        report = self.command("SG_ACCESS_TRANSPORT")
        responses = {case["scenario"]: case["status"] for case in report["interactions"]}
        self.assertEqual(responses["query"], 200, "反例必须真实展示违规渠道已取得身份")
        self.assertEqual(responses["form"], 200)
        self.repaired_project()
        self.access_fixture()
        self.command()

    def test_cookie_reading_is_rejected_even_with_native_prefix_support(self):
        self.access_fixture("sa-token.is-read-cookie=true\nsa-token.cookie-auto-fill-prefix=true")
        report = self.command("SG_ACCESS_TRANSPORT")
        self.assertEqual(next(case["status"] for case in report["interactions"] if case["scenario"] == "cookie"), 200)
        self.repaired_project()
        self.access_fixture()
        self.command()


class AccessCoverageCommands(SaAccessFixtures):
    def test_relabelled_absent_requests_cannot_replace_real_channel_counterexamples(self):
        for endpoint in self.definition["endpoints"]:
            for case in endpoint["scenarios"]:
                if case["id"] in {"wrong-prefix", "invalid", "query", "form", "cookie"}:
                    case["request"] = {"path": "/probe/session", "method": case["request"]["method"], "headers": {}, "body": None}
        self.command("SG_ACCESS_SCENARIOS")
        self.repaired_project()
        self.access_fixture()
        self.command()

    def test_missing_interceptor_cannot_pass_from_annotation_presence(self):
        app = self.project / "src/test-named-production/java/probe/http/App.java"
        app.write_text(app.read_text(encoding="utf-8").replace('addPathPatterns("/probe/session")', 'addPathPatterns("/never-matches")'), encoding="utf-8")
        report = self.command("SG_HTTP_SEMANTICS")
        self.assertEqual(next(case["status"] for case in report["interactions"] if case["scenario"] == "absent"), 200)
        self.repaired_project()
        self.access_fixture()
        self.command()


class AccessInputCommands(SaAccessFixtures):
    def test_missing_duplicate_or_unexecuted_channel_references_cannot_pass(self):
        original = dict(self.access_definition["transport"])
        for kind in ("missing", "duplicate", "unexecuted"):
            with self.subTest(kind=kind):
                self.access_definition["transport"] = dict(original)
                if kind == "missing":
                    del self.access_definition["transport"]["cookie"]
                else:
                    self.access_definition["transport"]["cookie"] = "absent" if kind == "duplicate" else "not-run"
                self.command("SG_ACCESS_SCENARIOS")
                self.assertFalse((self.project / "target").exists(), "无效访问合同应在构建前拒绝")
        self.access_definition["transport"] = original
        self.command()

    def test_missing_native_tools_does_not_skip_then_repairs(self):
        original = self.access_tools
        self.access_tools = str(self.root / "missing-access-tools")
        self.command("SG_ACCESS_TOOLS")
        self.assertFalse((self.project / "target").exists())
        self.access_tools = original
        self.command()


class AccessPartitionChecks(unittest.TestCase):
    def test_fixed_groups_cover_every_case_once_and_match_ci(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module, loader = sys.modules[__name__], unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "访问模块不得重复加载用例")
        selected = set()
        for check, names in (("access-transport-tests", ["AccessTransportCommands", "AccessPartitionChecks"]),
                             ("access-configuration-tests", ["AccessConfigurationCommands"]),
                             ("access-coverage-tests", ["AccessCoverageCommands"]),
                             ("access-input-tests", ["AccessInputCommands"])):
            self.assertIn(check, catalog["checks"], "访问校准分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "tools/engineering_gate/test_sa_access.py", *names],
                             "访问分组命令偏离登记")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"],
                             ["@python", "-B", "tools/engineering_gate/calibrate_access.py", "--environment"], "访问环境预检偏离固定入口")
            self.assertEqual(workflow.splitlines().count("        run: python -B tools/engineering_gate/test_sa_access.py " + " ".join(names)), 1,
                             "访问 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group)
            self.assertEqual(len(group), len(set(group)), "访问分组内用例重复")
            self.assertFalse(selected & set(group), "访问跨组用例重复")
            selected.update(group)
        self.assertEqual(selected, set(complete), "访问分组遗漏用例")


if __name__ == "__main__":
    unittest.main()
