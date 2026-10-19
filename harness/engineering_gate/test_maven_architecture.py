"""真实 Maven 产物、模块归属和合同选择的公开入口反例。"""

import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import urllib.parse
from types import SimpleNamespace
import xml.etree.ElementTree as ET
import zipfile

from test_maven_scope import MavenScopeFixtures


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_maven_architecture.py"


class MavenArchitectureCommands(MavenScopeFixtures):
    def setUp(self):
        super().setUp()
        self.tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(self.tools, "需要真实固定工具，不能跳过")
        self.seed_sources()
        self.definition = {"schema": 2, "modules": [
            {"id": "service", "package": "probe.service", "depends_on": [],
             "contract_types": ["probe.service.Service"],
             "contract_members": ["probe.service.Service.read():java.lang.String"]}]}

    def seed_sources(self):
        self.write("src/test-named-production/java/probe/Service.java",
                   'package probe.service; public class Service { /** 返回校准结果。 */ public String read() { return "ok"; } }')
        self.write("verification/java/probe/ServiceTest.java",
                   'package probe.service; class ServiceTest { String verify() { return new Service().read(); } }')

    def repaired_project(self):
        super().repaired_project()
        self.seed_sources()

    def command(self, diagnostic=None, *extra):
        declaration = self.project / "modules.json"
        declaration.write_text(json.dumps(self.definition), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project),
                                 "--declaration", str(declaration), "--maven-home", self.maven,
                                 "--repository", self.repository, "--tools", self.tools, *extra],
                                capture_output=True, text=True, timeout=30)
        self.assertIn(result.returncode, (0, 1), result.stdout + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, report)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], report)
        return report

    def test_unowned_actual_production_type_cannot_be_omitted(self):
        source = self.write("src/test-named-production/java/unowned/Hidden.java", "package unowned; class Hidden {}")
        report = self.command("SG_UNKNOWN_OWNER")
        self.assertTrue(report["build"]["ok"], report)
        self.assertTrue(any(item["type"] == "unowned.Hidden" and item["source"] == str(source) and
                            item["purpose"] == "production" for item in report["diagnostics"]
                            if item["code"] == "SG_UNKNOWN_OWNER"), report)

    def test_same_package_tests_share_module_but_not_production_contract(self):
        report = self.command()
        self.assertEqual(report["proof_scope"], "observed-maven-architecture-subset-only")
        bindings = {item["name"]: item for item in report["bindings"]}
        self.assertEqual({item["module"] for item in bindings.values()}, {"service"})
        self.assertEqual(bindings["probe.service.Service"]["purpose"], "production")
        self.assertEqual(bindings["probe.service.ServiceTest"]["purpose"], "test")
        self.assertEqual(bindings["probe.service.Service"]["source"], str(self.project / "src/test-named-production/java/probe/Service.java"))
        self.assertTrue(report["source_bindings_complete"])
        self.assertTrue(report["module_ownership_bound"])
        self.assertTrue(report["declared_contracts_bound"])
        self.assertEqual(report["contracts"][0]["members"][0]["id"], "probe.service.Service.read():java.lang.String")
        self.assertFalse(report["contract_closure_proven"])
        self.assertFalse(report["method_documentation_proven"])
        self.assertFalse(report["human_acceptance"])

    def test_module_cannot_supply_purpose_or_use_old_explicit_schema(self):
        self.definition["modules"][0]["purpose"] = "test"
        self.command("SG_DECLARATION")
        self.assertFalse((self.project / "target").exists())
        self.definition["schema"] = 1
        self.command("SG_DECLARATION")
        self.assertFalse((self.project / "target").exists())
        self.definition["schema"] = 2
        del self.definition["modules"][0]["purpose"]
        self.command()

    def test_test_contract_and_return_type_drift_are_rejected_then_repaired(self):
        self.write("verification/java/probe/ServiceTest.java", "package probe.service; public class ServiceTest {}")
        self.definition["modules"][0]["contract_types"].append("probe.service.ServiceTest")
        report = self.command("SG_CONTRACT_PURPOSE")
        self.assertFalse(report["declared_contracts_bound"])
        self.assertNotIn("probe.service.ServiceTest", {item["name"] for item in report["contracts"]})
        self.repaired_project()
        self.definition["modules"][0]["contract_types"].remove("probe.service.ServiceTest")
        self.definition["modules"][0]["contract_members"] = ["probe.service.Service.read():int"]
        self.command("SG_CONTRACT_TARGET")
        self.repaired_project()
        self.definition["modules"][0]["contract_members"] = ["probe.service.Service.read():java.lang.String"]
        self.command()

    def test_real_payload_private_fields_cannot_bypass_contract_boundary(self):
        self.write("verification/java/probe/ServiceTest.java", "package probe.service; class ServiceTest { Object verify() { return new Service().read(); } }")
        self.write("src/test-named-production/java/probe/Service.java", "package probe.service; public class Service { /** 返回公开数据。 */ public Payload read() { return null; } }")
        self.write("src/test-named-production/java/probe/Payload.java", "package probe.service; public class Payload { private java.util.List<Hidden[]> values; }")
        hidden = self.write("src/test-named-production/java/probe/Hidden.java", "package probe.service; class Hidden {}")
        self.definition["modules"][0]["contract_types"].append("probe.service.Payload")
        self.definition["modules"][0]["data_contracts"] = ["probe.service.Payload"]
        self.definition["modules"][0]["contract_members"] = ["probe.service.Service.read():probe.service.Payload"]
        report = self.command("SG_CONTRACT_TYPE")
        self.assertTrue(report["build"]["ok"], report)
        self.assertTrue(any(item.get("target") == "probe.service.Hidden" and item.get("path")
                            for item in report["diagnostics"]), report)
        self.assertIn(str(hidden), {item["source"] for item in report["bindings"]})
        self.repaired_project()
        self.write("verification/java/probe/ServiceTest.java", "package probe.service; class ServiceTest { Object verify() { return new Service().read(); } }")
        self.write("src/test-named-production/java/probe/Service.java", "package probe.service; public class Service { /** 返回公开数据。 */ public Payload read() { return null; } }")
        self.write("src/test-named-production/java/probe/Payload.java", "package probe.service; public class Payload { private String value; }")
        repaired = self.command()
        self.assertTrue(repaired["payload_field_closure_checked"])
        self.assertFalse(repaired["contract_closure_proven"], "字段闭包不证明框架动态合同或语义正确")

    def test_local_anonymous_and_method_reference_internal_access_follow_real_module(self):
        self.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
public class Service {
  /** 返回校准结果。 */
  public String read() { return "ok"; }
  public String internal() { return "hidden"; }
}''')
        source = self.write("src/test-named-production/java/client/Reader.java", '''package probe.client;
class Reader {
  String run(probe.service.Service service) {
    class Local { String read() { return service.internal(); } }
    Runnable anonymous = new Runnable() { public void run() { service.internal(); } };
    java.util.function.Supplier<String> reference = service::internal;
    anonymous.run();
    return new Local().read() + reference.get();
  }
}''')
        self.definition["modules"].append({"id": "client", "package": "probe.client", "depends_on": ["service"],
                                            "contract_types": [], "contract_members": []})
        report = self.command("SG_INTERNAL_MEMBER")
        bindings = [item for item in report["bindings"] if item["module"] == "client"]
        self.assertEqual({item["name"] for item in bindings}, {"probe.client.Reader", "probe.client.Reader$1", "probe.client.Reader$1Local"})
        self.assertEqual({item["source"] for item in bindings}, {str(source)})
        self.assertTrue(any("Reader$1Local" in item.get("origin", "") for item in report["diagnostics"]))
        self.assertTrue(any("Reader$1." in item.get("origin", "") for item in report["diagnostics"]))
        self.repaired_project()
        self.write("src/test-named-production/java/client/Reader.java", '''package probe.client;
class Reader { String run(probe.service.Service service) { return service.read(); } }''')
        self.definition["modules"][1]["depends_on"] = []
        self.command("SG_UNDECLARED_DEPENDENCY")
        self.repaired_project()
        self.write("src/test-named-production/java/client/Reader.java", '''package probe.client;
class Reader { String run(probe.service.Service service) { return service.read(); } }''')
        self.definition["modules"][1]["depends_on"] = ["service"]
        self.command()

    def test_equal_source_names_in_different_packages_use_ast_not_file_layout(self):
        source = self.write("src/test-named-production/java/unrelated/Service.java", "package probe.other; class Service {} class Sibling {}")
        self.definition["modules"].append({"id": "other", "package": "probe.other", "depends_on": [],
                                            "contract_types": [], "contract_members": []})
        report = self.command()
        bindings = {item["name"]: item for item in report["bindings"]}
        for name in ("probe.other.Service", "probe.other.Sibling"):
            self.assertEqual(bindings[name]["source"], str(source))
            self.assertEqual(bindings[name]["module"], "other")
        self.assertNotEqual(bindings["probe.service.Service"]["source"], str(source))

    def test_missing_source_metadata_and_duplicate_binary_purposes_fail_explicitly(self):
        self.pom("<compilerArgs><arg>-g:none</arg></compilerArgs>")
        self.command("SG_SOURCE_BINDING")
        self.repaired_project()
        self.write("verification/java/probe/Service.java", "package probe.service; class Service {}")
        self.write("verification/java/probe/ServiceTest.java", "package probe.service; class ServiceTest {}")
        self.command("SG_BYTECODE_INPUT")
        self.repaired_project()
        self.command()

    def test_caller_cannot_replace_observed_inputs_and_missing_tools_do_not_build(self):
        declaration = self.project / "modules.json"
        declaration.write_text(json.dumps(self.definition), encoding="utf-8")
        for extra in (("--purpose", "test"), ("--sources", "Service.java"), ("--report", "old.json")):
            process = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project),
                                      "--declaration", str(declaration), *extra], capture_output=True, text=True, timeout=5)
            self.assertEqual(process.returncode, 2, process.stdout + process.stderr)
        self.tools = str(self.root / "absent-tools")
        self.command("SG_TOOL_INPUT")
        self.assertFalse((self.project / "target").exists())
        self.tools = os.environ["DOMAIN_FORGE_GATE_TOOLS"]
        self.command()
        self.command("SG_DIRTY_BUILD")

    def third_party_sources(self):
        pom = self.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<build>", '''<dependencies>
<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.20.0</version></dependency>
<dependency><groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId><version>1.14.0</version><scope>test</scope></dependency>
</dependencies><build>'''), encoding="utf-8")
        self.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
public class Service {
  /** 返回去除首尾空白的校准结果。 */
  public String read() { return org.apache.commons.lang3.StringUtils.trim(" ok "); }
}''')
        self.write("verification/java/probe/ServiceTest.java", '''package probe.service;
class ServiceTest {
  String verify() { return org.apache.commons.text.StringEscapeUtils.escapeHtml4(new Service().read()); }
}''')

    def test_real_third_party_classpaths_bind_without_importing_sdk_as_owned_types(self):
        self.third_party_sources()
        report = self.command()
        scopes = {item["purpose"]: item for item in report["build"]["compilations"]}
        self.assertEqual({pathlib.Path(path).name for path in scopes["production"]["classpath"] if path.endswith(".jar")},
                         {"commons-lang3-3.20.0.jar"})
        self.assertEqual({pathlib.Path(path).name for path in scopes["test"]["classpath"] if path.endswith(".jar")},
                         {"commons-lang3-3.20.0.jar", "commons-text-1.14.0.jar"})
        self.assertEqual({item["name"] for item in report["bindings"]}, {"probe.service.Service", "probe.service.ServiceTest"})
        self.assertTrue(report["declared_method_sources_bound"])
        self.assertTrue(report["declared_method_comments_checked"])
        self.assertTrue(report["build"]["classpath_artifacts"])
        dependencies = {item["target"]: item for owner in report["inventory"] for item in owner["dependencies"]}
        self.assertEqual(dependencies["org.apache.commons.lang3.StringUtils"]["fully_imported"], "true")
        self.assertEqual(dependencies["org.apache.commons.text.StringEscapeUtils"]["fully_imported"], "true")
        self.assertFalse(report["architecture_gate_proven"])
        self.assertFalse(report["human_acceptance"])

    def test_missing_partial_or_wrong_purpose_classpath_observation_is_rejected(self):
        import calibrate_maven
        from calibrate_source import ProbeFailure
        self.third_party_sources()
        home, _ = calibrate_maven.runtime(self.maven)
        project, inputs = calibrate_maven.validate_project(self.project)
        with tempfile.TemporaryDirectory(prefix="domain-forge-classpath-observation-") as temporary:
            document, _ = calibrate_maven.compile_project(home, project, pathlib.Path(self.repository), pathlib.Path(temporary))
        original = ET.tostring(document)
        self.assertIn("BUILD SUCCESS", (project / "target/maven-scope.log").read_text(encoding="utf-8"))
        production = document.find("compilation[@goal='compile']")
        testing = document.find("compilation[@goal='testCompile']")
        self.assertIsNotNone(production.find("classpath"), "必须由原生观察器提供，不能补写清单")
        self.assertIsNotNone(testing.find("classpath"))
        for variant in ("missing", "partial", "test-only-in-production", "unresolved-artifact"):
            with self.subTest(variant=variant):
                changed = ET.fromstring(original)
                scope = changed.find("compilation[@goal='compile']")
                if variant == "missing":
                    for path in list(scope.findall("classpath")):
                        scope.remove(path)
                elif variant == "partial":
                    scope.remove(scope.findall("classpath")[-1])
                elif variant == "test-only-in-production":
                    scope.append(ET.fromstring(ET.tostring(testing.findall("classpath")[-1])))
                else:
                    scope.find("classpathDependency").set("file", str(self.root / "missing.jar"))
                with self.assertRaises(ProbeFailure) as caught:
                    calibrate_maven.compilation_inventory(changed, project, inputs, self.repository)
                self.assertEqual(caught.exception.diagnostic["code"], "SG_BUILD_CLASSPATH")
        self.assertEqual(ET.tostring(document), original, "隔离副本不能改写原生证据")
        calibrate_maven.compilation_inventory(document, project, inputs, self.repository)

    def test_dependency_drift_after_build_is_not_reused_by_source_checks(self):
        import calibrate_maven
        import calibrate_maven_source
        from calibrate_source import ProbeFailure
        namespace = tempfile.TemporaryDirectory(prefix="domain-forge-classpath-", dir=self.repository)
        self.addCleanup(namespace.cleanup)
        group = pathlib.Path(namespace.name).name
        directory = pathlib.Path(namespace.name) / "fixture/1.0.0"
        directory.mkdir(parents=True)
        source = self.root / "Value.java"
        source.write_text('package vendor; public class Value { public static String read() { return "ok"; } }', encoding="utf-8")
        compiled = self.root / "vendor-classes"
        process = subprocess.run(["javac", "--release", "21", "-d", str(compiled), str(source)], capture_output=True, text=True, timeout=10)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        artifact = directory / "fixture-1.0.0.jar"
        with zipfile.ZipFile(artifact, "w") as archive:
            archive.write(compiled / "vendor/Value.class", "vendor/Value.class")
        (directory / "fixture-1.0.0.pom").write_text('<project><modelVersion>4.0.0</modelVersion><groupId>' + group +
            '</groupId><artifactId>fixture</artifactId><version>1.0.0</version></project>', encoding="utf-8")
        pom = self.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<build>", '<dependencies><dependency><groupId>' + group +
            '</groupId><artifactId>fixture</artifactId><version>1.0.0</version></dependency></dependencies><build>'), encoding="utf-8")
        self.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
public class Service { /** 返回校准值。 */ public String read() { return vendor.Value.read(); } }''')
        build = calibrate_maven.probe(SimpleNamespace(project=str(self.project), maven_home=self.maven, repository=self.repository))
        self.assertTrue(build.get("classpath_artifacts"), "实际构建必须快照原生依赖，而非只快照自有类")
        calibrate_maven_source.unchanged_build(build, self.project)
        original = artifact.read_bytes()
        artifact.write_bytes(original + b"actual-dependency-drift")
        with self.assertRaises(ProbeFailure) as caught:
            calibrate_maven_source.unchanged_build(build, self.project)
        self.assertEqual(caught.exception.diagnostic["code"], "SG_INPUT_CHANGED")
        artifact.write_bytes(original)
        calibrate_maven_source.unchanged_build(build, self.project)

    def test_uncalibrated_compiler_path_overrides_are_not_claimed_as_observed_defaults(self):
        for configuration in ("<compilerArgs><arg>-classpath</arg><arg>injected</arg></compilerArgs>",
                              "<compilerArguments><classpath>injected</classpath></compilerArguments>",
                              "<testCompilerArgument>-classpath injected</testCompilerArgument>",
                              "<compilePath><path>injected</path></compilePath>", "<fork>true</fork>"):
            with self.subTest(configuration=configuration):
                self.pom(configuration)
                self.command("SG_BUILD_CLASSPATH")
                self.assertFalse((self.project / "target").exists())
        self.pom()
        self.command()
        self.repaired_project()
        pom = self.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<properties>",
                       "<properties><maven.compiler.fork>true</maven.compiler.fork>"), encoding="utf-8")
        self.command("SG_BUILD_CLASSPATH")
        self.assertFalse((self.project / "target").exists())
        self.repaired_project()
        self.pom("<fork>false</fork><compilerId>javac</compilerId><multiReleaseOutput>false</multiReleaseOutput>")
        pom = self.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<properties>",
                       "<properties><maven.compiler.fork>false</maven.compiler.fork>"), encoding="utf-8")
        self.command()

    def test_sdk_type_resolution_does_not_borrow_the_gate_tools_different_version(self):
        pom = self.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<build>", '''<dependencies><dependency>
<groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>1.7.36</version>
</dependency></dependencies><build>'''), encoding="utf-8")
        self.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
public class Service {
  /** 返回校准服务的日志名称，不在检查阶段执行 SDK。 */
  public String read() { return org.slf4j.LoggerFactory.getLogger(Service.class).getName(); }
}''')
        report = self.command()
        dependency = next(item for owner in report["inventory"] for item in owner["dependencies"] if item["target"] == "org.slf4j.Logger")
        jar = pathlib.Path(self.repository) / "org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
        self.assertTrue(dependency["source_uri"].startswith("jar:file:"))
        location, entry = dependency["source_uri"][4:].split("!", 1)
        self.assertEqual(pathlib.Path(urllib.parse.unquote(urllib.parse.urlsplit(location).path)).resolve(), jar,
                         "门禁自身日志工具不能替代实际构建的依赖字节码")
        self.assertEqual(entry, "/org/slf4j/Logger.class")

    def test_manifest_linked_types_and_inherited_owners_require_observed_origins(self):
        namespace = tempfile.TemporaryDirectory(prefix="domain-forge-origin-", dir=self.repository)
        self.addCleanup(namespace.cleanup)
        group = pathlib.Path(namespace.name).name
        compiled = self.root / "vendor-classes"
        sources = []
        for name, body in (("Value", 'public static String read() { return "ok"; }'),
                           ("Base", 'public String inherited() { return "ok"; }'),
                           ("Facade", "")):
            source = self.root / (name + ".java")
            source.write_text("package vendor; public class " + name +
                              (" extends Base" if name == "Facade" else "") + " { " + body + " }", encoding="utf-8")
            sources.append(str(source))
        process = subprocess.run(["javac", "--release", "21", "-d", str(compiled), *sources],
                                 capture_output=True, text=True, timeout=10)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        foreign = self.root / "unobserved-sdk.jar"
        with zipfile.ZipFile(foreign, "w") as artifact:
            for name in ("Value", "Base"):
                artifact.write(compiled / ("vendor/" + name + ".class"), "vendor/" + name + ".class")
        directory = pathlib.Path(namespace.name) / "carrier/1.0.0"
        directory.mkdir(parents=True)
        with zipfile.ZipFile(directory / "carrier-1.0.0.jar", "w") as artifact:
            artifact.write(compiled / "vendor/Facade.class", "vendor/Facade.class")
            artifact.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nClass-Path: " + foreign.as_uri() + "\r\n\r\n")
        (directory / "carrier-1.0.0.pom").write_text('<project><modelVersion>4.0.0</modelVersion><groupId>' + group +
            '</groupId><artifactId>carrier</artifactId><version>1.0.0</version></project>', encoding="utf-8")
        source = '''package probe.service;
public class Service {
  /** 返回隔离供应库的校准结果。 */
  public String read() { return vendor.Value.read() + new vendor.Facade().inherited(); }
}'''
        pom = self.project / "pom.xml"
        dependency = '<dependencies><dependency><groupId>' + group + '</groupId><artifactId>carrier</artifactId>' + \
                     '<version>1.0.0</version></dependency></dependencies>'
        pom.write_text(pom.read_text(encoding="utf-8").replace("<build>", dependency + "<build>"), encoding="utf-8")
        self.write("src/test-named-production/java/probe/Service.java", source)
        report = self.command("SG_DEPENDENCY_ORIGIN")
        self.assertTrue(report["build"]["ok"], "必须是成功构建后的来源违规，不是编译失败")
        self.assertNotIn(str(foreign), report["build"]["classpath_artifacts"])
        origins = [item for item in report["diagnostics"] if item["code"] == "SG_DEPENDENCY_ORIGIN"]
        self.assertTrue(any(item["target"] == "vendor.Value" for item in origins), origins)
        self.assertTrue(any(item["target"] == "vendor.Base" and item["reference_kind"] == "resolved-owner" for item in origins), origins)
        self.repaired_project()
        with zipfile.ZipFile(directory / "carrier-1.0.0.jar", "w") as artifact:
            for name in ("Value", "Base", "Facade"):
                artifact.write(compiled / ("vendor/" + name + ".class"), "vendor/" + name + ".class")
        pom = self.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<build>", dependency + "<build>"), encoding="utf-8")
        self.write("src/test-named-production/java/probe/Service.java", source)
        repaired = self.command()
        self.assertTrue(repaired["build"]["classpath_artifacts"])
        self.assertFalse(repaired["architecture_gate_proven"])
        self.assertFalse(repaired["human_acceptance"])


class MavenArchitecturePartitionChecks(unittest.TestCase):
    """固定语义分组由真实加载器核对，不依赖 Maven 或 Java。"""

    def test_fixed_groups_cover_all_architecture_cases_exactly_once(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        module = sys.modules[__name__]
        loader = unittest.TestLoader()
        complete = set(identifiers(loader.loadTestsFromModule(module)))
        selected = set()
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        for check, names in (("maven-architecture-tests", ["MavenArchitectureContractSuite", "MavenArchitecturePartitionChecks"]),
                             ("maven-architecture-purpose-tests", ["MavenArchitecturePurposeSuite"]),
                             ("maven-architecture-binding-tests", ["MavenArchitectureBindingSuite"]),
                             ("maven-architecture-classpath-tests", ["MavenArchitectureClasspathSuite"])):
            self.assertIn(check, catalog["checks"], "架构绑定固定分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "harness/engineering_gate/test_maven_architecture.py", *names],
                             "架构绑定固定分组命令偏离登记合同")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"],
                             ["@python", "-B", "harness/engineering_gate/calibrate_maven_architecture.py", "--environment"],
                             "架构绑定环境预检偏离固定入口")
            command = "python -B harness/engineering_gate/test_maven_architecture.py " + " ".join(names)
            self.assertEqual(workflow.splitlines().count("        run: " + command), 1,
                             "架构绑定 CI 命令必须与固定分组一致且仅执行一次")
            self.assertTrue(all(name in vars(module) for name in names), "架构绑定分组不存在")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group, "架构绑定固定分组不能为空")
            self.assertEqual(len(group), len(set(group)), "架构绑定用例在固定分组内重复选取")
            group = set(group)
            self.assertFalse(selected & group, "架构绑定用例被固定分组重复选取")
            selected.update(group)
        self.assertEqual(selected, complete, "固定分组遗漏架构绑定用例")


def MavenArchitectureContractSuite():
    """精确合同及跨模块实际访问责任。"""
    names = (
        "test_test_contract_and_return_type_drift_are_rejected_then_repaired",
        "test_real_payload_private_fields_cannot_bypass_contract_boundary",
        "test_local_anonymous_and_method_reference_internal_access_follow_real_module",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["MavenArchitectureCommands." + name for name in names], sys.modules[__name__])


def MavenArchitecturePurposeSuite():
    """模块归属及实际编译用途来源。"""
    names = (
        "test_unowned_actual_production_type_cannot_be_omitted",
        "test_same_package_tests_share_module_but_not_production_contract",
        "test_module_cannot_supply_purpose_or_use_old_explicit_schema",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["MavenArchitectureCommands." + name for name in names], sys.modules[__name__])


def MavenArchitectureBindingSuite():
    """源码及字节码唯一关联、缺失信息和调用方输入边界。"""
    names = (
        "test_equal_source_names_in_different_packages_use_ast_not_file_layout",
        "test_missing_source_metadata_and_duplicate_binary_purposes_fail_explicitly",
        "test_caller_cannot_replace_observed_inputs_and_missing_tools_do_not_build",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["MavenArchitectureCommands." + name for name in names], sys.modules[__name__])


def MavenArchitectureClasspathSuite():
    """真实构建的第三方类路径、用途及依赖漂移，不把 SDK 归入自有模块。"""
    names = (
        "test_real_third_party_classpaths_bind_without_importing_sdk_as_owned_types",
        "test_missing_partial_or_wrong_purpose_classpath_observation_is_rejected",
        "test_dependency_drift_after_build_is_not_reused_by_source_checks",
        "test_uncalibrated_compiler_path_overrides_are_not_claimed_as_observed_defaults",
        "test_sdk_type_resolution_does_not_borrow_the_gate_tools_different_version",
        "test_manifest_linked_types_and_inherited_owners_require_observed_origins",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["MavenArchitectureCommands." + name for name in names], sys.modules[__name__])


if __name__ == "__main__":
    unittest.main()
