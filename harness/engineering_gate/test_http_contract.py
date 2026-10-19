"""真实 Boot 4 注册、生成与 HTTP 正反例；独立旧合同不能被当前生成物替换。"""

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import unittest

import test_maven_scope


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_http.py"


class HttpContractFixtures(test_maven_scope.MavenScopeFixtures):
    def setUp(self):
        super().setUp()
        self.http_tools = os.environ.get("DOMAIN_FORGE_GATE_HTTP_TOOLS", "")
        self.contract = self.root / "scenarios.json"
        self.released = self.root / "released-0.9.0.json"
        self.definition = {"schema": 1, "version": "1.0.0", "application": "probe.http.App", "package": "probe.http", "endpoints": []}
        self.fixture()
        self.released.write_text(json.dumps(self.published()), encoding="utf-8")
        self.released_sha = hashlib.sha256(self.released.read_bytes()).hexdigest()

    def fixture(self, payload_type="String", value='"ok"', hidden="", extra_field="", inclusion=""):
        versions = json.loads((HERE / "maven-scope.lock.json").read_text(encoding="utf-8"))
        self.write("pom.xml", '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>probe</groupId><artifactId>http-contract</artifactId><version>1.0.0</version>
<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
<dependencyManagement><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>4.0.8</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
<dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
<dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-api</artifactId><version>3.0.3</version></dependency></dependencies>
<build><sourceDirectory>src/test-named-production/java</sourceDirectory><testSourceDirectory>verification/java</testSourceDirectory>
<plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>''' + versions["resources_version"] + '''</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>''' + versions["compiler_version"] + '''</version><configuration><parameters>true</parameters></configuration></plugin></plugins></build></project>''')
        self.write("src/test-named-production/java/probe/http/App.java", '''package probe.http;
/** 合同校准专用空应用，不提供产品业务能力。 */
@org.springframework.boot.autoconfigure.SpringBootApplication
public class App {
  /** 从唯一代码来源声明当前合同版本。 */
  @org.springframework.context.annotation.Bean
  public io.swagger.v3.oas.models.OpenAPI api() {
    return new io.swagger.v3.oas.models.OpenAPI().info(new io.swagger.v3.oas.models.info.Info().title("隔离校准").version("1.0.0"));
  }
}
''')
        self.write("src/test-named-production/java/probe/http/Payload.java", '''package probe.http;
/** 校准数据字段的真实序列化与合同。 */
public record Payload(@io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) ''' + payload_type + ''' value) {}
''')
        self.write("src/test-named-production/java/probe/http/Envelope.java", '''package probe.http;
/** HTTP 三字段外壳，仅用于隔离机制校准。 */
''' + inclusion + '''
@io.swagger.v3.oas.annotations.media.Schema(additionalProperties = io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
public record Envelope(
 @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) String code,
 @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) String message,
 @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED, schemaResolution = io.swagger.v3.oas.annotations.media.Schema.SchemaResolution.INLINE, nullable = true) Payload data''' + extra_field + ''') {}
''')
        addition = ", true" if extra_field else ""
        self.write("src/test-named-production/java/probe/http/Controller.java", '''package probe.http;
/** 固定 HTTP 场景的隔离入口，不实现 IAM 或领域业务。 */
@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping(produces = "application/json")
public class Controller {
  /** 返回一个正常结果，用于对照既有合同。 */
  ''' + hidden + '''
  @org.springframework.web.bind.annotation.GetMapping("/probe/result")
  public Envelope result() { return new Envelope("SUCCESS", "完成", new Payload(''' + value + ''')''' + addition + '''); }
  /** 返回显式业务拒绝，不以 HTTP 200 推断成功。 */
  @org.springframework.web.bind.annotation.GetMapping("/probe/rejected")
  public Envelope rejected() { return new Envelope("PROBE_REJECTED", "拒绝", null''' + addition + '''); }
  /** 返回仅受理结果，不宣称完成。 */
  @org.springframework.web.bind.annotation.GetMapping("/probe/accepted")
  public Envelope accepted() { return new Envelope("ACCEPTED", "受理", null''' + addition + '''); }
  /** 返回无数据的成功结果，仍保留 data 字段。 */
  @org.springframework.web.bind.annotation.GetMapping("/probe/no-data")
  public Envelope noData() { return new Envelope("SUCCESS", "完成", null''' + addition + '''); }
  /** 返回真实 HTTP 协议失败状态。 */
  @org.springframework.web.bind.annotation.GetMapping("/probe/bad")
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Envelope bad() { return new Envelope("BAD_REQUEST", "请求无效", null''' + addition + '''); }
  /** 保留非 JSON 协议，不给下载内容强套外壳。 */
  @org.springframework.web.bind.annotation.GetMapping(value = "/probe/download", produces = "application/octet-stream")
  public byte[] download() { return new byte[] {65, 66}; }
}
''')
        self.definition["endpoints"] = [
            self.endpoint("/probe/result", "SUCCESS", {"value": "ok"}),
            self.endpoint("/probe/rejected", "PROBE_REJECTED", None),
            self.endpoint("/probe/accepted", "ACCEPTED", None),
            self.endpoint("/probe/no-data", "SUCCESS", None),
            self.endpoint("/probe/bad", "BAD_REQUEST", None, status=400),
            {"path": "/probe/download", "method": "GET", "protocol": "binary", "scenarios": [
                {"id": "download", "request": {"path": "/probe/download", "method": "GET", "headers": {}, "body": None},
                 "status": 200, "content_type": "application/octet-stream", "body_base64": "QUI="}]}
        ]

    def endpoint(self, path, code, data, status=200):
        return {"path": path, "method": "GET", "protocol": "json", "scenarios": [
            {"id": path.rsplit("/", 1)[-1], "request": {"path": path, "method": "GET", "headers": {}, "body": None},
             "status": status, "code": code, "data": data}]}

    def published(self):
        return {"openapi": "3.0.1", "info": {"title": "已发布校准合同", "version": "0.9.0"},
                "paths": {"/probe/result": {"get": {"responses": {"200": {"description": "完成", "content": {
                    "application/json": {"schema": {"type": "object", "required": ["code", "message", "data"], "properties": {
                        "code": {"type": "string"}, "message": {"type": "string"},
                        "data": {"type": "object", "nullable": True, "required": ["value"], "properties": {"value": {"type": "string"}}}
                    }}}}}}}}}}

    def command(self, diagnostic=None):
        self.contract.write_text(json.dumps(self.definition), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project),
                                 "--scenarios", str(self.contract), "--baseline", str(self.released),
                                 "--baseline-sha256", self.released_sha, "--maven-home", self.maven,
                                 "--repository", self.repository, "--http-tools", self.http_tools],
                                capture_output=True, text=True, timeout=30)
        self.assertIn(result.returncode, (0, 1), "真实 HTTP 合同入口未提供目标行为：" + result.stdout + result.stderr)
        self.assertTrue(result.stdout.strip(), result.stderr)
        report = json.loads(result.stdout)
        logs = "\n".join(path.name + "\n" + path.read_text(encoding="utf-8")[-4500:]
                         for path in sorted((self.project / "target").glob("http-*.log")))
        detail = json.dumps({key: report.get(key) for key in
                             ("ok", "diagnostics", "compatibility", "openapi", "registered")}, ensure_ascii=False) + "\n" + logs
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, detail)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], detail)
        return report


class HttpGenerationCommands(HttpContractFixtures):
    def test_scenario_cannot_claim_one_route_while_requesting_another(self):
        case = next(endpoint["scenarios"][0] for endpoint in self.definition["endpoints"] if endpoint["path"] == "/probe/no-data")
        case["request"]["path"] = "/probe/rejected"
        case["code"] = "PROBE_REJECTED"
        self.command("SG_HTTP_DISPATCH")
        self.repaired_project()
        self.fixture()
        self.command()

    def test_real_registered_generated_and_runtime_contracts_agree(self):
        report = self.command()
        self.assertTrue(report["build"]["ok"])
        self.assertEqual(len(report["registered"]), 6)
        self.assertEqual(len(report["interactions"]), 6)
        self.assertTrue(report["runtime_schema_checked"])
        self.assertTrue(report["released_compatibility_checked"])
        self.assertEqual(report["openapi"]["openapi"], "3.0.1")
        self.assertFalse(report["product_verified"])
        self.assertFalse(report["semantic_review_proven"])
        self.assertFalse(report["human_acceptance"])

    def test_hidden_registered_handler_cannot_disappear_from_generated_contract(self):
        self.fixture(hidden="@io.swagger.v3.oas.annotations.Hidden")
        report = self.command("SG_HTTP_GENERATION_COVERAGE")
        self.assertTrue(report["build"]["ok"])
        self.assertIn("/probe/result", {item["path"] for item in report["registered"]})
        self.repaired_project()
        self.fixture()
        self.command()


class HttpRequestCommands(HttpContractFixtures):
    def post(self):
        path = self.project / "src/test-named-production/java/probe/http/Controller.java"
        content = path.read_text(encoding="utf-8")
        content = content.replace("public class Controller {", '''public class Controller {
  /** 通过真实请求类型回显，供成熟验证器检查请求和响应。 */
  @org.springframework.web.bind.annotation.PostMapping("/probe/echo")
  public Envelope echo(@org.springframework.web.bind.annotation.RequestBody Payload input) {
    return new Envelope("SUCCESS", "完成", input);
  }
''')
        path.write_text(content, encoding="utf-8")
        case = self.endpoint("/probe/echo", "SUCCESS", {"value": "ok"})
        case["method"] = "POST"
        case["scenarios"][0]["request"].update(method="POST", headers={"Content-Type": "application/json"}, body='{"value":"ok"}')
        self.definition["endpoints"].append(case)

    def test_actual_post_request_is_validated_not_just_the_success_response(self):
        self.post()
        report = self.command()
        self.assertTrue(report["runtime_schema_checked"])
        self.assertEqual(len(report["registered"]), 7)
        self.assertEqual(next(item for item in report["interactions"] if item["scenario"] == "echo")["body"]["data"], {"value": "ok"})

    def test_request_schema_rejects_coercion_even_when_actual_response_matches(self):
        self.post()
        case = self.definition["endpoints"][-1]["scenarios"][0]
        case["request"]["body"] = '{"value":42}'
        case["data"] = {"value": "42"}
        report = self.command("SG_HTTP_SCHEMA")
        self.assertTrue(any("request.body" in item.get("key", "") for item in report["diagnostics"]))
        self.assertEqual(next(item for item in report["interactions"] if item["scenario"] == "echo")["body"]["data"], {"value": "42"})
        self.repaired_project()
        self.fixture()
        self.post()
        self.command()

    def test_inherited_and_composed_mapping_is_not_missed(self):
        path = self.project / "src/test-named-production/java/probe/http/Controller.java"
        content = path.read_text(encoding="utf-8")
        start = content.index("  /** 返回一个正常结果")
        end = content.index("  /** 返回显式业务拒绝")
        method = content[start:end].replace('@org.springframework.web.bind.annotation.GetMapping("/probe/result")', "@ProbeRoute")
        self.write("src/test-named-production/java/probe/http/BaseController.java", "package probe.http;\n/** 继承的合法入口。 */\npublic class BaseController {\n" + method + "}\n")
        self.write("src/test-named-production/java/probe/http/ProbeRoute.java", '''package probe.http;
/** 组合路由，仅作为实际框架注册的合法校准。 */
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
@org.springframework.web.bind.annotation.RequestMapping(path = "/probe/result", method = org.springframework.web.bind.annotation.RequestMethod.GET)
public @interface ProbeRoute {}
''')
        path.write_text((content[:start] + content[end:]).replace("public class Controller {", "public class Controller extends BaseController {"), encoding="utf-8")
        report = self.command()
        self.assertTrue(report["runtime_schema_checked"])
        handler = next(item for item in report["registered"] if item["path"] == "/probe/result")
        self.assertIn("BaseController.result()", handler["handler"])


class HttpScopeCommands(HttpContractFixtures):
    def test_functional_router_cannot_hide_outside_annotation_handler_inventory(self):
        path = self.project / "src/test-named-production/java/probe/http/App.java"
        text = path.read_text(encoding="utf-8").replace("public class App {", '''public class App {
  /** 注册非注解入口，验证当前适配不能漏检后放行。 */
  @org.springframework.context.annotation.Bean
  public org.springframework.web.servlet.function.RouterFunction<org.springframework.web.servlet.function.ServerResponse> functional() {
    return org.springframework.web.servlet.function.RouterFunctions.route()
      .GET("/probe/functional", request -> org.springframework.web.servlet.function.ServerResponse.ok().body("registered"))
      .build();
  }
''')
        path.write_text(text, encoding="utf-8")
        self.command("SG_HTTP_ROUTE_SCOPE")
        self.repaired_project()
        self.fixture()
        self.command()

    def test_all_requests_cannot_be_marked_outside_schema_validation(self):
        for endpoint in self.definition["endpoints"]:
            for case in endpoint["scenarios"]:
                case["valid_request"] = False
        self.command("SG_HTTP_SCENARIO_COVERAGE")
        self.assertFalse((self.project / "target").exists())
        self.fixture()
        self.command()

    def test_custom_resource_url_cannot_hide_as_framework_static_mapping(self):
        self.write("src/test-named-production/java/probe/http/Resources.java", '''package probe.http;
/** 自定义资源入口不能由框架处理器类型自动获得覆盖豁免。 */
@org.springframework.context.annotation.Configuration
public class Resources implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
  /** 注册应被当前适配报告为未校准的额外 URL。 */
  @Override
  public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/probe/public/**").addResourceLocations("classpath:/public/");
  }
}
''')
        self.command("SG_HTTP_ROUTE_SCOPE")
        self.repaired_project()
        self.fixture()
        self.command()

    def test_nonempty_default_static_resources_need_explicit_protocol_calibration(self):
        self.write("src/main/resources/static/probe-static.txt", "not-an-annotation-route")
        self.command("SG_HTTP_ROUTE_SCOPE")
        self.repaired_project()
        self.fixture()
        self.command()


class HttpParameterCommands(HttpContractFixtures):
    def test_variable_route_and_more_specific_route_use_actual_dispatch_not_pattern_guessing(self):
        def routes():
            path = self.project / "src/test-named-production/java/probe/http/Controller.java"
            path.write_text(path.read_text(encoding="utf-8").replace("public class Controller {", '''public class Controller {
  /** 验证路径变量与更具体静态入口的真实分派。 */
  @org.springframework.web.bind.annotation.GetMapping("/probe/items/{id}")
  public Envelope item(@org.springframework.web.bind.annotation.PathVariable String id) {
    return new Envelope("SUCCESS", "完成", new Payload("ok"));
  }
  /** 即使响应相同也不能被另一入口的场景冒充。 */
  @org.springframework.web.bind.annotation.GetMapping("/probe/items/fixed")
  public Envelope fixed() { return new Envelope("SUCCESS", "完成", new Payload("ok")); }
'''), encoding="utf-8")
            variable = self.endpoint("/probe/items/{id}", "SUCCESS", {"value": "ok"})
            variable["scenarios"][0]["id"] = "variable-item"
            variable["scenarios"][0]["request"]["path"] = "/probe/items/one"
            self.definition["endpoints"].extend([variable, self.endpoint("/probe/items/fixed", "SUCCESS", {"value": "ok"})])

        routes()
        self.definition["endpoints"][-2]["scenarios"][0]["request"]["path"] = "/probe/items/fixed"
        self.command("SG_HTTP_DISPATCH")
        self.repaired_project()
        self.fixture()
        routes()
        report = self.command()
        actual = next(case for case in report["interactions"] if case["scenario"] == "variable-item")
        self.assertEqual(actual["dispatch"]["pattern"], "/probe/items/{id}")

    def test_query_parameters_use_real_uri_and_native_schema_validator(self):
        path = self.project / "src/test-named-production/java/probe/http/Controller.java"
        content = path.read_text(encoding="utf-8").replace("public class Controller {", '''public class Controller {
  /** 查询参数通过真实请求和原生合同分别核对。 */
  @org.springframework.web.bind.annotation.GetMapping("/probe/query")
  public Envelope query(@org.springframework.web.bind.annotation.RequestParam java.util.List<String> value) {
    return new Envelope("SUCCESS", "完成", new Payload(String.join("|", value)));
  }
''')
        path.write_text(content, encoding="utf-8")
        case = self.endpoint("/probe/query", "SUCCESS", {"value": "a b+c|z"})
        case["scenarios"][0]["request"]["path"] += "?value=a+b%2Bc&value=z"
        self.definition["endpoints"].append(case)
        report = self.command()
        self.assertTrue(report["runtime_schema_checked"])
        self.assertEqual(next(item for item in report["interactions"] if item["scenario"] == "query")["body"]["data"], {"value": "a b+c|z"})


class HttpDriftCommands(HttpContractFixtures):
    def test_runtime_schema_mismatch_rejects_then_repairs(self):
        path = self.project / "src/test-named-production/java/probe/http/Payload.java"
        path.write_text(path.read_text(encoding="utf-8").replace("requiredMode =", 'type = "integer", requiredMode ='), encoding="utf-8")
        report = self.command("SG_HTTP_SCHEMA")
        self.assertTrue(report["build"]["ok"])
        self.assertTrue(any(item["body"]["data"]["value"] == "ok" for item in report["interactions"] if item["scenario"] == "result"))
        self.repaired_project()
        self.fixture()
        self.command()

    def test_source_and_generator_changing_together_cannot_reset_published_contract(self):
        self.fixture(payload_type="Integer", value="42")
        self.definition["endpoints"][0]["scenarios"][0]["data"] = {"value": 42}
        report = self.command("SG_HTTP_BREAKING")
        self.assertTrue(report["runtime_schema_checked"], "当前实现与当前生成物一致仍须由独立已发布合同拒绝")
        self.assertFalse(report["compatibility"]["compatible"])
        self.repaired_project()
        self.fixture()
        self.command()


class HttpEnvelopeCommands(HttpContractFixtures):
    def test_extra_success_field_is_rejected_even_if_generator_matches(self):
        self.fixture(extra_field=", boolean success")
        report = self.command("SG_HTTP_ENVELOPE")
        self.assertTrue(report["runtime_schema_checked"])
        self.repaired_project()
        self.fixture()
        self.command()

    def test_omitting_null_data_and_relabeling_rejection_are_not_success(self):
        self.fixture(inclusion="@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)")
        report = self.command("SG_HTTP_ENVELOPE")
        self.assertTrue(report["build"]["ok"])
        self.repaired_project()
        self.fixture()
        path = self.project / "src/test-named-production/java/probe/http/Controller.java"
        path.write_text(path.read_text(encoding="utf-8").replace('"PROBE_REJECTED"', '"SUCCESS"'), encoding="utf-8")
        report = self.command("SG_HTTP_SEMANTICS")
        self.assertTrue(report["runtime_schema_checked"])
        self.repaired_project()
        self.fixture()
        self.command()


class HttpInputCommands(HttpContractFixtures):
    def test_generated_contract_version_cannot_silently_remain_default(self):
        original = self.released.read_bytes()
        self.released.write_text("[]", encoding="utf-8")
        self.released_sha = hashlib.sha256(self.released.read_bytes()).hexdigest()
        self.command("SG_HTTP_INPUT")
        self.assertFalse((self.project / "target").exists())
        self.released.write_bytes(original)
        self.released_sha = hashlib.sha256(original).hexdigest()
        path = self.project / "src/test-named-production/java/probe/http/App.java"
        path.write_text(path.read_text(encoding="utf-8").replace('version("1.0.0")', 'version("v0")'), encoding="utf-8")
        self.command("SG_HTTP_VERSION")
        self.repaired_project()
        self.fixture()
        self.command()

    def test_published_digest_and_scenario_coverage_are_mandatory(self):
        self.released.write_text(json.dumps({"openapi": "3.0.1"}), encoding="utf-8")
        self.command("SG_HTTP_BASELINE")
        self.released.write_text(json.dumps(self.published()), encoding="utf-8")
        self.definition["endpoints"].pop(0)
        report = self.command("SG_HTTP_SCENARIO_COVERAGE")
        self.assertTrue(report["build"]["ok"])
        self.repaired_project()
        self.fixture()
        self.command()

    def test_wrong_http_status_and_missing_tools_are_not_passed(self):
        self.http_tools = str(self.root / "missing-tools")
        self.command("SG_HTTP_TOOLS")
        self.http_tools = os.environ["DOMAIN_FORGE_GATE_HTTP_TOOLS"]
        path = self.project / "src/test-named-production/java/probe/http/Controller.java"
        path.write_text(path.read_text(encoding="utf-8").replace("HttpStatus.BAD_REQUEST", "HttpStatus.OK"), encoding="utf-8")
        report = self.command("SG_HTTP_SEMANTICS")
        self.assertTrue(report["build"]["ok"])
        self.repaired_project()
        self.fixture()
        self.command()


class HttpToolQualityChecks(unittest.TestCase):
    def test_owned_adapters_keep_comments_format_and_approved_metrics(self):
        tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(tools, "必须提供真实源码工具，不能跳过适配器质量检查")
        sources = [HERE / name for name in ("HttpInventory.java", "HttpValidation.java", "SaAccessInventory.java", "DomainForgeUnassignedProbe.java")]
        for action in ("comments", "method-presence", "format", "complexity"):
            with self.subTest(action=action):
                argv = [sys.executable, "-B", str(HERE / "calibrate_source.py"), "--tools", tools, action]
                if action in ("comments", "complexity"):
                    argv.extend(["--purpose", "production"])
                result = subprocess.run([*argv, *map(str, sources)], capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                self.assertEqual(set(report["scanned"]), set(map(str, sources)))
                if action == "complexity":
                    self.assertTrue(report["coverage"]["complete"])
                    self.assertTrue(report["coverage"]["implementations"])


class HttpPartitionChecks(unittest.TestCase):
    def test_fixed_groups_cover_every_case_exactly_once_and_match_ci(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module, loader = sys.modules[__name__], unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "HTTP 模块加载重复用例")
        selected = set()
        for check, names in (("http-generation-tests", ["HttpGenerationCommands", "HttpToolQualityChecks", "HttpPartitionChecks"]),
                             ("http-request-tests", ["HttpRequestCommands"]),
                             ("http-parameter-tests", ["HttpParameterCommands"]),
                             ("http-drift-tests", ["HttpDriftCommands"]),
                             ("http-envelope-tests", ["HttpEnvelopeCommands"]),
                             ("http-input-tests", ["HttpInputCommands"]),
                             ("http-scope-tests", ["HttpScopeCommands"])):
            self.assertIn(check, catalog["checks"], "HTTP 固定分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "harness/engineering_gate/test_http_contract.py", *names],
                             "HTTP 固定分组命令偏离登记合同")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"],
                             ["@python", "-B", "harness/engineering_gate/calibrate_http.py", "--environment"],
                             "HTTP 环境预检偏离固定入口")
            command = "        run: python -B harness/engineering_gate/test_http_contract.py " + " ".join(names)
            self.assertEqual(workflow.splitlines().count(command), 1, "HTTP CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group, "HTTP 固定分组不能为空")
            self.assertEqual(len(group), len(set(group)), "HTTP 组内重复用例")
            self.assertFalse(selected & set(group), "HTTP 跨组重复用例")
            selected.update(group)
        self.assertEqual(selected, set(complete), "HTTP 固定分组遗漏用例")


if __name__ == "__main__":
    unittest.main()
