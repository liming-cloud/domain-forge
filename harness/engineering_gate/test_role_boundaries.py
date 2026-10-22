"""真实 Maven 与框架类型的职责边界反例，不建立产品工程或声称事务已生效。"""

import json
import hashlib
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

import test_maven_architecture


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_roles.py"


class RoleBoundaryCommands(unittest.TestCase):
    def setUp(self):
        self.case = test_maven_architecture.MavenArchitectureCommands()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)
        self.lock = json.loads((HERE / "role-boundaries.lock.json").read_text(encoding="utf-8"))

    def dependencies(self):
        text = "".join('<dependency><groupId>org.springframework</groupId><artifactId>' + artifact +
                       '</artifactId><version>' + self.lock["spring_version"] + '</version></dependency>'
                       for artifact in ("spring-context", "spring-beans", "spring-tx", "spring-jdbc", "spring-core"))
        pom = self.case.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<build>", "<dependencies>" + text + "</dependencies><build>"), encoding="utf-8")

    def source(self, role, annotation="", expression='"ok"', extra=""):
        package = "probe.service." + role
        self.case.write("src/test-named-production/java/probe/Service.java", "package " + package + ";\n/** 隔离工程中的职责校准类型。 */\n" + annotation + '''
public class Service {
  /** 返回职责校准结果。 */
  public String read() { return ''' + expression + "; }\n" + extra + "\n}")
        self.case.write("verification/java/probe/ServiceTest.java", "package " + package +
                        '; class ServiceTest { String verify() { return new Service().read(); } }')
        self.case.definition = {"schema": 3, "modules": [
            {"id": "service", "package": "probe.service", "kind": "context", "depends_on": [],
             "contract_types": [package + ".Service"], "contract_members": [package + ".Service.read():java.lang.String"]}]}
        self.dependencies()

    def command(self, diagnostic=None):
        declaration = self.case.project / "modules.json"
        declaration.write_text(json.dumps(self.case.definition), encoding="utf-8")
        extra = ["--contract-sha256", hashlib.sha256(declaration.read_bytes()).hexdigest()] if self.case.definition["schema"] == 4 else []
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.case.project),
                                 "--declaration", str(declaration), "--maven-home", self.case.maven,
                                 "--repository", self.case.repository, "--tools", self.case.tools, *extra],
                                capture_output=True, text=True, timeout=30)
        self.assertIn(result.returncode, (0, 1), result.stdout + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, report)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], report)
        return report

    def strict_definition(self):
        self.case.definition["schema"] = 4
        for module in self.case.definition["modules"]:
            module.update(api_categories={}, api_uses={}, query_bindings=[])

    def fresh_copy(self):
        self.case.repair_count += 1
        target = self.case.root / ("copy-" + str(self.case.repair_count))
        shutil.copytree(self.case.project, target, ignore=shutil.ignore_patterns("target"))
        self.case.project = target

    def component(self, category, position):
        self.source(position, expression="probe.shared.Api.read()")
        self.strict_definition()
        self.case.write("src/test-named-production/java/shared/Api.java", '''package probe.shared;
/** 代表一种明确公开边界的合成组件。 */
public class Api { /** 返回校准值。 */ public static String read() { return "ok"; } }
''')
        service = self.case.definition["modules"][0]
        service.update(depends_on=["shared"], api_uses={"probe.shared.Api": [position]})
        self.case.definition["modules"].append(dict(id="shared", package="probe.shared", kind="component", depends_on=[],
            contract_types=["probe.shared.Api"], contract_members=["probe.shared.Api.read():java.lang.String"],
            api_categories={"probe.shared.Api": category}, api_uses={}, query_bindings=[]))

    def test_component_categories_follow_actual_calling_position(self):
        for category, position, rejected in (("neutral", "application", "domain"),
                ("http", "interfaces.http", "application"), ("client", "infrastructure.persistence", "interfaces.http"),
                ("configuration", "bootstrap", "infrastructure.persistence")):
            with self.subTest(category=category):
                self.case.repaired_project()
                self.component(category, position)
                self.assertTrue(self.command()["build"]["ok"])
                self.fresh_copy()
                path = self.case.project / "src/test-named-production/java/probe/Service.java"
                path.write_text(path.read_text().replace("probe.service." + position, "probe.service." + rejected))
                test = self.case.project / "verification/java/probe/ServiceTest.java"
                test.write_text(test.read_text().replace("probe.service." + position, "probe.service." + rejected))
                module = self.case.definition["modules"][0]
                module["contract_types"] = ["probe.service." + rejected + ".Service"]
                module["contract_members"] = ["probe.service." + rejected + ".Service.read():java.lang.String"]
                report = self.command("SG_COMPONENT_POSITION")
                self.assertTrue(report["build"]["ok"], "必须实际编译成功后识别调用层违规")

    def test_neutral_component_cannot_leak_technical_payload(self):
        self.component("neutral", "application")
        path = self.case.project / "src/test-named-production/java/shared/Api.java"
        path.write_text(path.read_text().replace('public static String read()', 'public javax.sql.DataSource source; public static String read()'))
        self.assertTrue(self.command("SG_COMPONENT_PAYLOAD")["build"]["ok"])
        self.fresh_copy()
        path = self.case.project / "src/test-named-production/java/shared/Api.java"
        path.write_text(path.read_text().replace('public javax.sql.DataSource source; ', ''))
        self.assertTrue(self.command()["ok"])

    def test_query_contract_requires_real_override_and_implementation_position(self):
        self.source("application.query")
        self.strict_definition()
        self.case.write("src/test-named-production/java/probe/Service.java", '''package probe.service.application.query;
/** 技术无关的查询合同。 */
public interface Service { /** 返回查询视图。 */ String read(); }
''')
        self.case.write("src/test-named-production/java/query/SqlQueries.java", '''package probe.service.infrastructure.query;
/** 实现查询接口的校准适配器。 */
public class SqlQueries implements probe.service.application.query.Service {
  @Override public String read() { return "ok"; }
}
''')
        self.case.write("verification/java/probe/ServiceTest.java", '''package probe.service.application.query;
class ServiceTest { String verify() { return new probe.service.infrastructure.query.SqlQueries().read(); } }
''')
        module = self.case.definition["modules"][0]
        module["query_bindings"] = [{"contract": "probe.service.application.query.Service.read():java.lang.String",
                                     "implementation": "probe.service.infrastructure.query.SqlQueries.read():java.lang.String"}]
        self.assertTrue(self.command()["ok"])
        self.fresh_copy()
        path = self.case.project / "src/test-named-production/java/query/SqlQueries.java"
        path.write_text(path.read_text().replace(' implements probe.service.application.query.Service', '').replace('@Override', '/** 返回伪查询值。 */'))
        self.assertTrue(self.command("SG_QUERY_IMPLEMENTATION")["build"]["ok"])
        self.fresh_copy()
        path = self.case.project / "src/test-named-production/java/query/SqlQueries.java"
        path.write_text(path.read_text().replace('public class SqlQueries {', 'public class SqlQueries implements probe.service.application.query.Service {').replace('probe.service.infrastructure.query', 'probe.service.application.query'))
        test = self.case.project / "verification/java/probe/ServiceTest.java"
        test.write_text(test.read_text().replace('probe.service.infrastructure.query', 'probe.service.application.query'))
        module["query_bindings"][0]["implementation"] = "probe.service.application.query.SqlQueries.read():java.lang.String"
        self.assertTrue(self.command("SG_QUERY_IMPLEMENTATION")["build"]["ok"])

    def test_external_component_uses_actual_jar_identity_and_same_category(self):
        self.component("neutral", "application")
        namespace = tempfile.TemporaryDirectory(prefix="domain-forge-component-", dir=self.case.repository)
        self.addCleanup(namespace.cleanup)
        group = pathlib.Path(namespace.name).name
        directory = pathlib.Path(namespace.name) / "fixture/1.0.0"
        directory.mkdir(parents=True)
        source = self.case.project / "src/test-named-production/java/shared/Api.java"
        classes = self.case.root / "component-classes"
        p = subprocess.run(["javac", "--release", "21", "-d", str(classes), str(source)], capture_output=True, text=True, timeout=10)
        self.assertEqual(p.returncode, 0, p.stderr)
        jar = directory / "fixture-1.0.0.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.write(classes / "probe/shared/Api.class", "probe/shared/Api.class")
        (directory / "fixture-1.0.0.pom").write_text('<project><modelVersion>4.0.0</modelVersion><groupId>' + group +
            '</groupId><artifactId>fixture</artifactId><version>1.0.0</version></project>')
        source.unlink()
        pom = self.case.project / "pom.xml"
        pom.write_text(pom.read_text().replace('<dependencies>', '<dependencies><dependency><groupId>' + group +
            '</groupId><artifactId>fixture</artifactId><version>1.0.0</version></dependency>', 1))
        module = self.case.definition["modules"][1]
        module["artifact"] = dict(group=group, artifact="fixture", version="1.0.0", sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
        self.assertTrue(self.command()["ok"])
        self.fresh_copy()
        module["api_categories"]["probe.shared.Api"] = "http"
        self.command("SG_COMPONENT_POSITION")
        self.fresh_copy()
        module["api_categories"]["probe.shared.Api"] = "neutral"
        module["artifact"]["sha256"] = "0" * 64
        self.command("SG_COMPONENT_ARTIFACT")

    def test_fixed_module_contract_rejects_kind_change_and_missing_digest(self):
        self.source("bootstrap")
        self.strict_definition()
        declaration = self.case.project / "modules.json"
        declaration.write_text(json.dumps(self.case.definition))
        approved = hashlib.sha256(declaration.read_bytes()).hexdigest()
        self.case.definition["modules"][0]["kind"] = "assembly"
        declaration.write_text(json.dumps(self.case.definition))
        argv = [sys.executable, "-B", str(ENTRY), "--project", str(self.case.project), "--declaration", str(declaration),
                "--maven-home", self.case.maven, "--repository", self.case.repository, "--tools", self.case.tools]
        for extra in ([], ["--contract-sha256", approved]):
            p = subprocess.run([*argv, *extra], capture_output=True, text=True, timeout=30)
            self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
            self.assertEqual(json.loads(p.stdout)["diagnostics"][0]["code"], "SG_MODULE_CONTRACT")
            self.assertFalse((self.case.project / "target").exists())
        self.case.definition["schema"] = 3
        for name in ("api_categories", "api_uses", "query_bindings"):
            self.case.definition["modules"][0].pop(name)
        declaration.write_text(json.dumps(self.case.definition))
        p = subprocess.run([*argv, "--contract-sha256", approved], capture_output=True, text=True, timeout=30)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertEqual(json.loads(p.stdout)["diagnostics"][0]["code"], "SG_MODULE_CONTRACT")
        self.assertFalse((self.case.project / "target").exists(), "旧 schema 也不能忽略调用方固定的合同")

    def test_domain_markers_require_exact_artifact_and_empty_jdk_only_interfaces(self):
        self.source("domain")
        self.strict_definition()
        package = "org.domainforge.shared"
        names = [package + "." + name for name in ("Entity", "AggregateRoot", "ValueObject")]
        sources = []
        for name in ("Entity", "AggregateRoot", "ValueObject"):
            path = self.case.root / (name + ".java")
            path.write_text("package " + package + "; public interface " + name +
                            (" extends Entity" if name == "AggregateRoot" else "") + " {}")
            sources.append(path)
        dto = self.case.root / "Response.java"
        dto.write_text("package " + package + "; public record Response(String value) {}")
        sources.append(dto)
        version = "1.0.0-probe." + self.case.root.name.rsplit("-", 1)[-1]
        directory = pathlib.Path(self.case.repository) / "org/domainforge/shared" / version
        directory.mkdir(parents=True)
        self.addCleanup(shutil.rmtree, directory)
        jar = directory / ("shared-" + version + ".jar")
        classes = self.case.root / "markers"

        def build_jar():
            process = subprocess.run(["javac", "--release", "21", "-d", str(classes), *map(str, sources)],
                                     capture_output=True, text=True, timeout=10)
            self.assertEqual(process.returncode, 0, process.stderr)
            with zipfile.ZipFile(jar, "w") as archive:
                for path in classes.rglob("*.class"):
                    archive.write(path, path.relative_to(classes))
            return hashlib.sha256(jar.read_bytes()).hexdigest()

        checksum = build_jar()
        (directory / ("shared-" + version + ".pom")).write_text(
            "<project><modelVersion>4.0.0</modelVersion><groupId>org.domainforge</groupId>"
            "<artifactId>shared</artifactId><version>" + version + "</version></project>")
        pom = self.case.project / "pom.xml"
        pom.write_text(pom.read_text().replace("<dependencies>", "<dependencies><dependency>"
            "<groupId>org.domainforge</groupId><artifactId>shared</artifactId><version>" +
            version + "</version></dependency>", 1))
        source = self.case.project / "src/test-named-production/java/probe/Service.java"
        source.write_text(source.read_text().replace("public class Service {",
            "public class Service implements " + package + ".AggregateRoot {"))
        self.case.definition["modules"][0].update(depends_on=["domain"],
            api_uses={name: ["domain"] for name in names})
        component = dict(id="domain", package=package, kind="component", depends_on=[],
                         contract_types=names, contract_members=[],
                         api_categories={name: "domain-marker" for name in names},
                         api_uses={}, query_bindings=[], artifact=dict(group="org.domainforge",
                         artifact="shared", version=version, sha256=checksum))
        component["contract_types"].append(package + ".Response")
        component["api_categories"][package + ".Response"] = "http"
        component["contract_members"].append(package + ".Response.<init>(java.lang.String):void")
        self.case.definition["modules"].append(component)
        self.assertTrue(self.command()["ok"])
        self.fresh_copy()
        component["artifact"]["artifact"] = "unrelated-library"
        self.command("SG_DOMAIN_MARKER")
        component["artifact"]["artifact"] = "shared"
        self.fresh_copy()
        source = self.case.project / "src/test-named-production/java/probe/Service.java"
        original = source.read_text()
        source.write_text(original.replace('return "ok";', 'return new ' + package + '.Response("ok").value();'))
        self.assertTrue(self.command("SG_COMPONENT_POSITION")["build"]["ok"])
        source.write_text(original)
        self.fresh_copy()
        sources[0].write_text("package " + package + "; public interface Entity { javax.sql.DataSource SOURCE = null; }")
        component["artifact"]["sha256"] = build_jar()
        self.assertTrue(self.command("SG_DOMAIN_MARKER")["build"]["ok"])

    def test_strict_contract_rejects_malformed_or_unfixed_input_before_build(self):
        for mutation in ("unknown-category", "unknown-position", "unhashable-position", "floating-artifact", "overlap"):
            with self.subTest(mutation=mutation):
                self.case.repaired_project()
                self.component("neutral", "application")
                service, component = self.case.definition["modules"]
                code = "SG_COMPONENT_DECLARATION"
                if mutation == "unknown-category": component["api_categories"]["probe.shared.Api"] = "anything"
                if mutation == "unknown-position": service["api_uses"]["probe.shared.Api"] = ["*"]
                if mutation == "unhashable-position": service["api_uses"]["probe.shared.Api"] = [["application"]]
                if mutation == "floating-artifact": component["artifact"] = dict(group="probe", artifact="api", version="LATEST", sha256="0"*64)
                if mutation == "overlap":
                    component["package"] = "probe.service.child"
                    code = "SG_DECLARATION"
                self.command(code)
                self.assertFalse((self.case.project / "target").exists())
        declaration = self.case.project / "modules.json"
        for text in ("[]", "{"):
            declaration.write_text(text)
            p = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.case.project),
                                "--declaration", str(declaration)], capture_output=True, text=True, timeout=30)
            self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
            self.assertEqual(json.loads(p.stdout)["diagnostics"][0]["code"], "SG_DECLARATION")

    def test_strict_component_requires_real_public_members_and_closed_payload(self):
        for mutation, code in (("missing-type", "SG_COMPONENT_TARGET"), ("missing-member", "SG_COMPONENT_TARGET"),
                               ("internal-member", "SG_INTERNAL_MEMBER"), ("transitive", "SG_COMPONENT_PAYLOAD")):
            with self.subTest(mutation=mutation):
                self.case.repaired_project()
                self.component("neutral", "application")
                component = self.case.definition["modules"][1]
                if mutation == "missing-type":
                    component["contract_types"].append("probe.shared.Missing")
                    component["api_categories"]["probe.shared.Missing"] = "neutral"
                if mutation == "missing-member": component["contract_members"].append("probe.shared.Api.absent():void")
                if mutation == "internal-member": component["contract_members"] = []
                if mutation == "transitive":
                    self.case.write("src/test-named-production/java/shared/View.java", 'package probe.shared; /** 中间载荷。 */ public class View { public javax.sql.DataSource source; }')
                    path = self.case.project / "src/test-named-production/java/shared/Api.java"
                    path.write_text(path.read_text().replace('public class Api {', 'public class Api { public View value;'))
                    component["contract_types"].append("probe.shared.View")
                    component["api_categories"]["probe.shared.View"] = "neutral"
                self.command(code)

    def test_existing_web_api_is_http_only_in_product_quality_entry(self):
        import calibrate_flyway
        sys.path.insert(0, str(HERE.parent / "product_gate"))
        import build
        self.source("interfaces.http")
        self.strict_definition()
        root = HERE.parents[1]
        api = root / "platform/framework/domain-forge-web-support/src/main/java/org/domainforge/web/api/ApiResponse.java"
        self.case.write("src/test-named-production/java/web/ApiResponse.java", api.read_text().rstrip())
        source = self.case.project / "src/test-named-production/java/probe/Service.java"
        source.write_text(source.read_text().replace('return "ok";', 'org.domainforge.web.api.ApiResponse.success("ok"); return "ok";'))
        pom = self.case.project / 'pom.xml'
        text = pom.read_text()
        extra = '<dependency><groupId>org.springframework</groupId><artifactId>spring-web</artifactId></dependency>'
        extra += '<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-annotations</artifactId></dependency>'
        version = json.loads((HERE.parent/'product_gate/web-baseline.json').read_text())['tools']['openapi_annotations']['version']
        extra += '<dependency><groupId>io.swagger.core.v3</groupId><artifactId>swagger-annotations-jakarta</artifactId><version>' + version + '</version></dependency>'
        bom = '<dependencyManagement><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>' + calibrate_flyway.baseline()['boot_version'] + '</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>'
        pom.write_text(text.replace('<dependencies>', bom + '<dependencies>' + extra, 1))
        component = json.loads((HERE.parent/'product_gate/web-modules.json').read_text())['modules'][0]
        component['contract_types'] = ['org.domainforge.web.api.ApiResponse']
        component['contract_members'] = [member for member in component['contract_members'] if member.startswith('org.domainforge.web.api.ApiResponse.')]
        component['api_categories'] = {'org.domainforge.web.api.ApiResponse': 'http'}
        self.case.definition['modules'].append(component)
        service = self.case.definition['modules'][0]
        service.update(depends_on=['web-support'], api_uses={'org.domainforge.web.api.ApiResponse': ['interfaces.http']})
        for role, diagnostic in [('interfaces.http', None), ('application', 'SG_COMPONENT_POSITION')]:
            if role == 'application':
                self.fresh_copy()
                for file in self.case.project.rglob('*.java'):
                    file.write_text(file.read_text().replace('probe.service.interfaces.http', 'probe.service.application'))
                service['contract_types'] = ['probe.service.application.Service']
                service['contract_members'] = ['probe.service.application.Service.read():java.lang.String']
            formatter = pathlib.Path(self.case.tools)/'google-java-format-1.37.0-all-deps.jar'
            formatted = subprocess.run(['java', '-jar', str(formatter), '--replace', *map(str,self.case.project.rglob('*.java'))], capture_output=True, text=True, timeout=30)
            self.assertEqual(formatted.returncode, 0, formatted.stderr)
            report = self.command(diagnostic)
            declaration = self.case.project/'modules.json'
            result = build.quality(root, self.case.project, {'build':report['build']}, self.case.tools,
                                   str(declaration), hashlib.sha256(declaration.read_bytes()).hexdigest())
            self.assertEqual(result['ok'], diagnostic is None, result['diagnostics'])
            if diagnostic:
                self.assertIn(diagnostic, {item['code'] for item in result['diagnostics']})

    def test_boot4_line_calibrates_actual_spring7_metadata(self):
        self.source("application", "@org.springframework.stereotype.Service\n@org.springframework.transaction.annotation.Transactional")
        report = self.command()
        versions = {item["version"] for compilation in report["build"]["compilations"]
                    for item in compilation["dependencies"] if item["group"] == "org.springframework"}
        self.assertEqual(versions, {self.lock["spring_version"]}, "校准必须来自同次实际解析的框架对象")
        self.assertEqual({version.split(".")[0] for version in versions}, {"7"}, "Boot 4 不能由 Spring 6 夹具代替适配")
        self.assertTrue(report["role_boundaries_checked"])
        self.assertFalse(report["architecture_gate_proven"])
        self.assertFalse(report["human_acceptance"])

    def test_context_bootstrap_is_owned_and_reverse_dependencies_are_rejected(self):
        self.source("bootstrap")
        report = self.command()
        self.assertEqual(report["role_bindings"]["probe.service.bootstrap.Service"], "bootstrap")
        for role in ("interfaces", "application", "domain", "infrastructure"):
            with self.subTest(role=role):
                self.case.repaired_project()
                self.source(role, expression="new probe.service.bootstrap.Wiring().read()")
                self.case.write("src/test-named-production/java/bootstrap/Wiring.java", '''package probe.service.bootstrap;
/** 只供反向引用反例使用的装配类型。 */
public class Wiring { public String read() { return "ok"; } }
''')
                report = self.command("SG_ROLE_DEPENDENCY")
                self.assertTrue(report["build"]["ok"])

    def test_domain_cannot_bind_framework_lifecycle_then_repairs(self):
        self.source("domain", "@org.springframework.stereotype.Service")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"], "必须原生成功后因职责边界拒绝")
        self.case.repaired_project()
        self.source("domain")
        self.assertTrue(self.command()["role_boundaries_checked"])

    def test_application_cannot_operate_client_even_with_allowed_annotations_then_repairs(self):
        self.source("application", "@org.springframework.stereotype.Service", "new org.springframework.jdbc.core.JdbcTemplate().toString()")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"], "不能把编译失败当作技术操作拦截")
        self.case.repaired_project()
        self.source("application", "@org.springframework.stereotype.Service")
        self.assertTrue(self.command()["role_boundaries_checked"])

    def composed_annotation(self, role):
        self.source(role, "@probe.shared.UseCase")
        self.case.write("src/test-named-production/java/shared/UseCase.java", '''package probe.shared;
/** 组合装配注解，仅用于校准间接框架绑定。 */
@org.springframework.stereotype.Service
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface UseCase {}
''')
        self.case.definition["modules"][0]["depends_on"] = ["shared"]
        self.case.definition["modules"].append({"id": "shared", "package": "probe.shared", "kind": "component",
                                               "depends_on": [], "contract_types": ["probe.shared.UseCase"], "contract_members": []})

    def test_composed_annotation_does_not_hide_domain_framework_binding(self):
        self.composed_annotation("domain")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        self.case.repaired_project()
        self.composed_annotation("application")
        self.assertTrue(self.command()["role_boundaries_checked"])

    def test_application_metadata_does_not_require_technical_wrapper_interfaces(self):
        self.source("application", '''@org.springframework.stereotype.Service
@org.springframework.transaction.annotation.Transactional(
 propagation=org.springframework.transaction.annotation.Propagation.REQUIRED,
 isolation=org.springframework.transaction.annotation.Isolation.DEFAULT)''', 'port.read()', '''
  private final Port port;
  /** 接收业务协议，不操作具体客户端。 */
  @org.springframework.beans.factory.annotation.Autowired
  public Service(@org.springframework.beans.factory.annotation.Qualifier("business") Port port) { this.port = port; }
}
/** 校准用技术无关的业务协议。 */
interface Port { String read(); }
/** 校准合法组件元数据。 */
@org.springframework.stereotype.Component
class Companion {
''')
        self.case.write("verification/java/probe/ServiceTest.java", '''package probe.service.application;
class ServiceTest { String verify() { return new Service(() -> "ok").read(); } }
''')
        report = self.command()
        self.assertTrue(report["build"]["ok"])
        self.assertTrue(report["role_boundaries_checked"])
        for key in ("source_gate_proven", "architecture_gate_proven", "contract_closure_proven", "human_acceptance"):
            self.assertFalse(report[key], "装配及事务注解不证明产品事务、完整架构或人工验收")

    def test_annotation_class_literal_cannot_reuse_metadata_permission_then_repairs(self):
        annotation = "@org.springframework.stereotype.Service"
        self.source("application", annotation, 'org.springframework.stereotype.Service.class.getName()')
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        references = [item for item in report["dependency_declarations"]
                      if item["owner"] == "probe.service.application.Service" and item["target"] == "org.springframework.stereotype.Service"]
        self.assertTrue({"annotation", "type"}.issubset({item["usage"] for item in references}))
        self.assertTrue(report["build"]["ok"])
        self.case.repaired_project()
        self.source("application", annotation)
        self.command()

    def test_static_imported_enum_operation_is_not_annotation_metadata_then_repairs(self):
        self.source("application", "@org.springframework.transaction.annotation.Transactional(propagation=REQUIRED)",
                    'Integer.toString(REQUIRED.value())')
        path = self.case.project / "src/test-named-production/java/probe/Service.java"
        text = path.read_text(encoding="utf-8")
        path.write_text(text.replace(";\n", ";\nimport static org.springframework.transaction.annotation.Propagation.REQUIRED;\n", 1), encoding="utf-8")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        self.assertTrue(any(item.get("operation") is True for item in report["diagnostics"]
                            if item["code"] == "SG_TECHNICAL_DEPENDENCY"))
        self.case.repaired_project()
        self.source("application", "@org.springframework.transaction.annotation.Transactional")
        self.command()

    def test_inlined_sql_constant_keeps_consumer_for_named_and_wildcard_imports(self):
        for imported in ("java.sql.Statement.RETURN_GENERATED_KEYS", "java.sql.Statement.*"):
            with self.subTest(imported=imported):
                self.source("domain", expression="Integer.toString(RETURN_GENERATED_KEYS)")
                path = self.case.project / "src/test-named-production/java/probe/Service.java"
                text = path.read_text(encoding="utf-8").replace(";\n", ";\nimport static " + imported + ";\n", 1)
                path.write_text(text, encoding="utf-8")
                report = self.command("SG_TECHNICAL_DEPENDENCY")
                self.assertTrue(report["build"]["ok"])
                self.assertNotIn("java.sql.Statement", {part["target"] for item in report["inventory"] for part in item["dependencies"]},
                                 "原生字节码确实没有该依赖，不能靠编译失败或字节码反例冒充")
                self.assertTrue(any(item["target"] == "java.sql.Statement" and item["usage"] == "type" and
                                    item["owner"] == "probe.service.domain.Service" for item in report["dependency_declarations"]))
            self.case.repaired_project()
        self.source("domain")
        self.command()

    def test_nonpublic_domain_data_source_dependency_is_rejected_then_repairs(self):
        self.source("domain", extra="private javax.sql.DataSource connection;")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        self.assertTrue(any(item["target"] == "javax.sql.DataSource" for item in report["diagnostics"]
                            if item["code"] == "SG_TECHNICAL_DEPENDENCY"))
        self.case.repaired_project()
        self.source("domain")
        self.command()

    def test_local_and_anonymous_application_code_cannot_use_infrastructure_types(self):
        self.source("application", expression='new Local().read()', extra="")
        path = self.case.project / "src/test-named-production/java/probe/Service.java"
        path.write_text('''package probe.service.application;
/** 校准局部与匿名实现的真实所属职责。 */
public class Service {
  /** 返回校准结果。 */
  public String read() {
    class Local { String read() { return new probe.service.infrastructure.Stored().read(); } }
    java.util.function.Supplier<String> anonymous = new java.util.function.Supplier<>() {
      public String get() { return new probe.service.infrastructure.Stored().read(); }
    };
    return new Local().read() + anonymous.get();
  }
}
''', encoding="utf-8")
        self.case.write("src/test-named-production/java/infra/Stored.java", '''package probe.service.infrastructure;
/** 仅用于检查职责，不代表产品持久化实现。 */
public class Stored { public String read() { return "ok"; } }
''')
        report = self.command("SG_ROLE_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        owners = {item["type"] for item in report["diagnostics"] if item["code"] == "SG_ROLE_DEPENDENCY"}
        self.assertTrue({"probe.service.application.Service$1Local", "probe.service.application.Service$1"}.issubset(owners))
        self.case.repaired_project()
        self.source("infrastructure", expression='new org.springframework.jdbc.core.JdbcTemplate().toString()')
        self.command()

    def test_context_roles_use_actual_package_and_tests_keep_actual_purpose(self):
        self.source("unclassified")
        report = self.command("SG_ROLE_OWNERSHIP")
        self.assertTrue(report["build"]["ok"])
        self.case.repaired_project()
        self.source("domain")
        self.case.write("verification/java/probe/ServiceTest.java", '''package probe.service.domain;
class ServiceTest { String verify() { return new org.springframework.jdbc.core.JdbcTemplate().toString(); } }
''')
        report = self.command()
        self.assertEqual(report["role_bindings"]["probe.service.domain.ServiceTest"], "test")

    def test_components_and_assembly_are_not_forced_into_context_positions(self):
        for kind in ("component", "assembly"):
            with self.subTest(kind=kind):
                self.source("runtime", "@org.springframework.stereotype.Service",
                            'new org.springframework.jdbc.core.JdbcTemplate().toString()')
                self.case.definition["modules"][0]["kind"] = kind
                self.assertEqual(self.command()["role_bindings"]["probe.service.runtime.Service"], kind)
                self.case.repaired_project()

    def test_unknown_library_is_a_calibration_gap_not_a_fabricated_coding_violation(self):
        self.source("domain", expression='org.apache.commons.text.StringEscapeUtils.escapeHtml4("ok")')
        pom = self.case.project / "pom.xml"
        text = pom.read_text(encoding="utf-8").replace("</dependencies>", '''<dependency>
<groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId><version>1.14.0</version>
</dependency></dependencies>''')
        pom.write_text(text, encoding="utf-8")
        report = self.command("SG_ROLE_CALIBRATION")
        self.assertTrue(report["build"]["ok"])
        self.assertNotIn("SG_TECHNICAL_DEPENDENCY", {item["code"] for item in report["diagnostics"]})
        self.case.repaired_project()
        self.source("domain", expression='org.apache.commons.lang3.StringUtils.trim(" ok ")')
        pom = self.case.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("</dependencies>", '''<dependency>
<groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.20.0</version>
</dependency></dependencies>'''), encoding="utf-8")
        self.command()

    def test_invalid_role_declaration_is_rejected_before_build_then_repairs(self):
        self.source("domain")
        baseline = json.loads(json.dumps(self.case.definition))
        for variant in ("legacy", "kind", "override", "unknown"):
            with self.subTest(variant=variant):
                self.case.definition = json.loads(json.dumps(baseline))
                if variant == "legacy":
                    self.case.definition["schema"] = 2
                elif variant == "kind":
                    self.case.definition["modules"][0]["kind"] = "test"
                elif variant == "override":
                    self.case.definition["modules"][0]["roles"] = {"domain": "runtime"}
                else:
                    self.case.definition["unexpected"] = True
                self.command("SG_DECLARATION")
                self.assertFalse((self.case.project / "target").exists())
        self.case.definition = baseline
        self.command()

    def test_invalid_role_baseline_fails_before_build_then_restores(self):
        self.source("domain")
        with tempfile.TemporaryDirectory(prefix="domain-forge-role-config-") as temporary:
            copied = pathlib.Path(temporary) / "isolated-checkout/harness/engineering_gate"
            shutil.copytree(HERE, copied, ignore=shutil.ignore_patterns("__pycache__"))
            lock = copied / "role-boundaries.lock.json"
            original = lock.read_text(encoding="utf-8")
            declaration = self.case.project / "modules.json"
            declaration.write_text(json.dumps(self.case.definition), encoding="utf-8")
            argv = [sys.executable, "-B", str(copied / ENTRY.name), "--project", str(self.case.project),
                    "--declaration", str(declaration), "--maven-home", self.case.maven,
                    "--repository", self.case.repository, "--tools", self.case.tools]
            for variant in ("wildcard", "wildcard-client", "version", "unknown", "overlap", "duplicate-key",
                            "technical-wildcard", "technical-version", "technical-overlap"):
                with self.subTest(variant=variant):
                    changed = json.loads(original)
                    if variant == "wildcard":
                        changed["application_annotations"] = ["org.springframework.*"]
                    elif variant == "wildcard-client":
                        changed["technical_types"] = ["org.springframework.*"]
                    elif variant == "version":
                        changed["spring_version"] = "LATEST"
                    elif variant == "unknown":
                        changed["permit_unknown"] = True
                    elif variant == "overlap":
                        changed["annotation_values"] = changed["application_annotations"]
                    elif variant == "technical-wildcard":
                        changed["technical_libraries"][0]["artifact"] = "mybatis-plus-*"
                    elif variant == "technical-version":
                        changed["technical_libraries"][0]["version"] = "LATEST"
                    elif variant == "technical-overlap":
                        changed["utility_libraries"].append(changed["technical_libraries"][0])
                    text = json.dumps(changed)
                    if variant == "duplicate-key":
                        text = '{"schema": 1,' + text[1:]
                    lock.write_text(text, encoding="utf-8")
                    process = subprocess.run(argv, capture_output=True, text=True, timeout=30)
                    self.assertEqual(process.returncode, 1, process.stdout + process.stderr)
                    self.assertEqual(json.loads(process.stdout)["diagnostics"][0]["code"], "SG_ROLE_BASELINE")
                    self.assertFalse((self.case.project / "target").exists())
            lock.write_text(original, encoding="utf-8")
            process = subprocess.run(argv, capture_output=True, text=True, timeout=30)
            self.assertEqual(process.returncode, 0, process.stdout + process.stderr)

    def test_missing_source_usage_observation_cannot_pass_then_restores(self):
        import calibrate_architecture
        import role_boundaries
        from calibrate_source import ProbeFailure
        self.source("application", "@org.springframework.stereotype.Service")
        report = self.command()
        modules = calibrate_architecture.declaration(self.case.project / "modules.json", schema=3)
        original = json.dumps(report)
        for variant in ("owner", "usage", "empty-owner", "unknown-owner"):
            with self.subTest(variant=variant):
                changed = json.loads(original)
                self.assertTrue(changed["dependency_declarations"])
                reference = next(item for item in changed["dependency_declarations"] if item["usage"] != "import")
                if variant == "empty-owner":
                    reference["owner"] = ""
                elif variant == "unknown-owner":
                    reference["owner"] = "probe.unobserved.Missing"
                else:
                    del reference[variant]
                with self.assertRaises(ProbeFailure) as caught:
                    role_boundaries.inspect(changed, modules, role_boundaries.baseline())
                self.assertEqual(caught.exception.diagnostic["code"], "SG_ROLE_SOURCE")
        self.assertFalse(role_boundaries.inspect(json.loads(original), modules, role_boundaries.baseline())[1])

    def test_missing_declaration_has_structured_failure_without_build(self):
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.case.project),
                                 "--declaration", str(self.case.root / "absent.json"), "--maven-home", self.case.maven,
                                 "--repository", self.case.repository, "--tools", self.case.tools],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)["diagnostics"][0]["code"], "SG_DECLARATION")
        self.assertFalse((self.case.project / "target").exists())

    def test_uncalibrated_spring_api_is_not_declared_technical_by_group_name(self):
        self.source("application", expression='org.springframework.util.StringUtils.trimAllWhitespace(" ok ")')
        report = self.command("SG_ROLE_CALIBRATION")
        self.assertTrue(report["build"]["ok"])
        self.assertNotIn("SG_TECHNICAL_DEPENDENCY", {item["code"] for item in report["diagnostics"]})


class RoleBoundaryPartitionChecks(unittest.TestCase):
    def test_fixed_groups_cover_every_case_exactly_once_and_match_ci(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module, loader = sys.modules[__name__], unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "职责模块加载重复用例")
        selected = set()
        for check, names in (("role-boundary-tests", ["RoleBoundaryPolicySuite", "RoleBoundaryPartitionChecks"]),
                             ("role-boundary-usage-tests", ["RoleBoundaryUsageSuite"]),
                             ("role-boundary-ownership-tests", ["RoleBoundaryOwnershipSuite"]),
                             ("role-boundary-input-tests", ["RoleBoundaryInputSuite"])):
            self.assertIn(check, catalog["checks"], "职责固定分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "harness/engineering_gate/test_role_boundaries.py", *names],
                             "职责固定分组命令偏离登记合同")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"],
                             ["@python", "-B", "harness/engineering_gate/calibrate_roles.py", "--environment"],
                             "职责环境预检偏离固定入口")
            command = "        run: python -B harness/engineering_gate/test_role_boundaries.py " + " ".join(names)
            self.assertEqual(workflow.splitlines().count(command), 1, "职责 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group, "职责固定分组不能为空")
            self.assertEqual(len(group), len(set(group)), "职责组内重复用例")
            self.assertFalse(selected & set(group), "职责跨组重复用例")
            selected.update(group)
        self.assertEqual(selected, set(complete), "职责固定分组遗漏用例")


def RoleBoundaryPolicySuite():
    names = ("test_component_categories_follow_actual_calling_position",
             "test_domain_markers_require_exact_artifact_and_empty_jdk_only_interfaces",
             "test_boot4_line_calibrates_actual_spring7_metadata",
             "test_domain_cannot_bind_framework_lifecycle_then_repairs",
             "test_application_cannot_operate_client_even_with_allowed_annotations_then_repairs",
             "test_application_metadata_does_not_require_technical_wrapper_interfaces",
             "test_static_imported_enum_operation_is_not_annotation_metadata_then_repairs")
    return unittest.defaultTestLoader.loadTestsFromNames(["RoleBoundaryCommands." + name for name in names], sys.modules[__name__])


def RoleBoundaryUsageSuite():
    names = ("test_neutral_component_cannot_leak_technical_payload",
             "test_external_component_uses_actual_jar_identity_and_same_category",
             "test_existing_web_api_is_http_only_in_product_quality_entry",
             "test_composed_annotation_does_not_hide_domain_framework_binding",
             "test_annotation_class_literal_cannot_reuse_metadata_permission_then_repairs",
             "test_inlined_sql_constant_keeps_consumer_for_named_and_wildcard_imports")
    return unittest.defaultTestLoader.loadTestsFromNames(["RoleBoundaryCommands." + name for name in names], sys.modules[__name__])


def RoleBoundaryOwnershipSuite():
    names = ("test_context_bootstrap_is_owned_and_reverse_dependencies_are_rejected",
             "test_query_contract_requires_real_override_and_implementation_position",
             "test_nonpublic_domain_data_source_dependency_is_rejected_then_repairs",
             "test_local_and_anonymous_application_code_cannot_use_infrastructure_types",
             "test_context_roles_use_actual_package_and_tests_keep_actual_purpose",
             "test_components_and_assembly_are_not_forced_into_context_positions")
    return unittest.defaultTestLoader.loadTestsFromNames(["RoleBoundaryCommands." + name for name in names], sys.modules[__name__])


def RoleBoundaryInputSuite():
    names = ("test_strict_contract_rejects_malformed_or_unfixed_input_before_build",
             "test_strict_component_requires_real_public_members_and_closed_payload",
             "test_fixed_module_contract_rejects_kind_change_and_missing_digest",
             "test_invalid_role_declaration_is_rejected_before_build_then_repairs",
             "test_invalid_role_baseline_fails_before_build_then_restores",
             "test_missing_source_usage_observation_cannot_pass_then_restores",
             "test_missing_declaration_has_structured_failure_without_build",
             "test_uncalibrated_spring_api_is_not_declared_technical_by_group_name",
             "test_unknown_library_is_a_calibration_gap_not_a_fabricated_coding_violation")
    return unittest.defaultTestLoader.loadTestsFromNames(["RoleBoundaryCommands." + name for name in names], sys.modules[__name__])


if __name__ == "__main__":
    unittest.main()
