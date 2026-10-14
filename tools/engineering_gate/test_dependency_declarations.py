"""真实编译源码后核对直接依赖和官方能力入口，不用命名或 BOM 自动放行。"""

import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

import test_maven_architecture


HERE = pathlib.Path(__file__).resolve().parent


class DependencyDeclarationCommands(unittest.TestCase):
    def setUp(self):
        self.case = test_maven_architecture.MavenArchitectureCommands()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)

    def dependencies(self, text, management=""):
        pom = self.case.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<build>",
                       management + "<dependencies>" + text + "</dependencies><build>"), encoding="utf-8")

    def junit(self, artifact, scope="test"):
        version = json.loads((HERE / "maven-lifecycle.lock.json").read_text(encoding="utf-8"))["junit_version"]
        return '<dependency><groupId>org.junit.jupiter</groupId><artifactId>' + artifact + '</artifactId><version>' + \
               version + '</version><scope>' + scope + '</scope></dependency>'

    def repository_namespace(self):
        namespace = tempfile.TemporaryDirectory(prefix="domain-forge-declarations-", dir=self.case.repository)
        self.addCleanup(namespace.cleanup)
        self.namespace = pathlib.Path(namespace.name)
        return self.namespace.name

    def local_artifact(self, name, dependency="", classes=()):
        directory = self.namespace / name / "1.0.0"
        directory.mkdir(parents=True)
        packaging = "jar" if classes else "pom"
        if classes:
            with zipfile.ZipFile(directory / (name + "-1.0.0.jar"), "w") as artifact:
                for item in classes:
                    artifact.write(self.case.root / "vendor-classes/vendor" / (item + ".class"), "vendor/" + item + ".class")
        (directory / (name + "-1.0.0.pom")).write_text('<project><modelVersion>4.0.0</modelVersion><groupId>' +
            self.namespace.name + '</groupId><artifactId>' + name + '</artifactId><version>1.0.0</version><packaging>' +
            packaging + '</packaging><dependencies>' + dependency + '</dependencies></project>', encoding="utf-8")
        return '<dependency><groupId>' + self.namespace.name + '</groupId><artifactId>' + name + \
               '</artifactId><version>1.0.0</version><type>' + packaging + '</type></dependency>'

    def vendor(self):
        self.repository_namespace()
        sources = []
        for name, body in (("Marker", '@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface Marker {}'),
                           ("Constants", 'public class Constants { public static final String VALUE = "ok"; }'),
                           ("Base", 'public class Base { public static final String VALUE = "ok"; public String inherited() { return "ok"; } }'),
                           ("Facade", 'public class Facade extends Base {}')):
            source = self.case.root / (name + ".java")
            source.write_text("package vendor; " + body, encoding="utf-8")
            sources.append(str(source))
        process = subprocess.run(["javac", "--release", "21", "-proc:none", "-d", str(self.case.root / "vendor-classes"), *sources],
                                 capture_output=True, text=True, timeout=10)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)

    def test_accidental_transitive_library_requires_direct_declaration_then_repairs(self):
        dependency = '''<dependency><groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId>
<version>1.14.0</version></dependency>'''
        source = '''package probe.service;
public class Service {
  /** 返回去除空白的校准值。 */
  public String read() { return org.apache.commons.lang3.StringUtils.trim(" ok "); }
}'''
        self.dependencies(dependency)
        self.case.write("src/test-named-production/java/probe/Service.java", source)
        report = self.case.command("SG_UNDECLARED_LIBRARY")
        self.assertTrue(report["build"]["ok"], "必须成功构建后识别实际使用的偶然传递库")
        self.assertTrue(any(item.get("target") == "org.apache.commons.lang3.StringUtils" for item in report["diagnostics"]), report)
        self.case.repaired_project()
        self.dependencies(dependency + '''<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId>
<version>3.20.0</version></dependency>''')
        self.case.write("src/test-named-production/java/probe/Service.java", source)
        repaired = self.case.command()
        self.assertTrue(repaired["source_dependency_declarations_checked"])
        self.assertFalse(repaired["source_gate_proven"])
        self.assertFalse(repaired["human_acceptance"])

    def test_official_aggregator_api_is_not_repeated_as_a_direct_dependency(self):
        self.dependencies(self.junit("junit-jupiter"))
        self.case.write("verification/java/probe/ServiceTest.java", '''package probe.service;
class ServiceTest {
  @org.junit.jupiter.api.Test void verify() { org.junit.jupiter.api.Assertions.assertEquals("ok", new Service().read()); }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = "ok")
  void verifyParameter(String value) { org.junit.jupiter.api.Assertions.assertEquals(value, new Service().read()); }
}''')
        report = self.case.command()
        reference = next(item for item in report["dependency_declarations"] if item["target"] == "org.junit.jupiter.api.Test")
        self.assertEqual(reference["basis"], "capability-api")
        self.assertEqual(reference["provider"]["artifact"], "junit-jupiter")
        self.assertEqual(reference["purpose"], "test")
        self.assertTrue(any(item["target"] == "org.junit.jupiter.params.ParameterizedTest" and item["basis"] == "capability-api"
                            for item in report["dependency_declarations"]))
        self.assertNotIn("junit-jupiter-api", {item["artifact"] for item in report["build"]["direct_dependencies"]})
        self.assertFalse(report["source_gate_proven"])
        self.assertFalse(report["human_acceptance"])

    def test_source_retention_annotation_and_inlined_constant_cannot_disappear(self):
        self.vendor()
        api = self.local_artifact("api", classes=("Marker", "Constants"))
        bridge = self.local_artifact("bridge", api)
        source = '''package probe.service;
@vendor.Marker
public class Service {
  /** 返回供应库的编译期常量。 */
  public String read() { return vendor.Constants.VALUE; }
}'''
        self.dependencies(bridge)
        self.case.write("src/test-named-production/java/probe/Service.java", source)
        report = self.case.command("SG_UNDECLARED_LIBRARY")
        self.assertTrue(report["build"]["ok"])
        dependencies = {item["target"] for owner in report["inventory"] for item in owner["dependencies"]}
        self.assertFalse({"vendor.Marker", "vendor.Constants"} & dependencies, "反例必须确实不出现在字节码依赖中")
        violations = {item.get("target") for item in report["diagnostics"] if item["code"] == "SG_UNDECLARED_LIBRARY"}
        self.assertTrue({"vendor.Marker", "vendor.Constants"} <= violations, report)
        self.case.repaired_project()
        self.dependencies(bridge + api)
        self.case.write("src/test-named-production/java/probe/Service.java", source)
        repaired = self.case.command()
        self.assertTrue(repaired["source_dependency_declarations_checked"])

    def test_sdk_inherited_implementation_owner_does_not_force_duplicate_declaration(self):
        self.vendor()
        base = self.local_artifact("base", classes=("Base",))
        facade = self.local_artifact("facade", base, ("Facade",))
        self.dependencies(facade)
        self.case.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
public class Service {
  /** 通过公开入口读取供应库结果。 */
  public String read() { return new vendor.Facade().inherited(); }
}''')
        report = self.case.command()
        self.assertTrue(any(item["resolved_owner"] == "vendor.Base" for owner in report["inventory"] for item in owner["accesses"]))
        self.assertEqual({item["artifact"] for item in report["build"]["direct_dependencies"]}, {"facade"})
        self.assertNotIn("vendor.Base", {item["target"] for item in report["dependency_declarations"]},
                         "SDK 内部继承者不是源码直接使用类型")
        self.assertTrue(report["source_dependency_declarations_checked"])
        self.case.repaired_project()
        self.dependencies(facade)
        self.case.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
import static vendor.Facade.*;
public class Service {
  /** 通过供应入口读取继承的编译期常量。 */
  public String read() { return VALUE; }
}''')
        report = self.case.command()
        references = report["dependency_declarations"]
        self.assertTrue(any(item["target"] == "vendor.Facade" and item["owner"] == "probe.service.Service" and
                            item["usage"] == "type" for item in references), "实际常量使用须关联源码入口而非只保留导入")
        self.assertNotIn("vendor.Base", {item["target"] for item in references}, "不能强加继承实现所在库的直接声明")

    def test_bom_and_starter_name_do_not_authorize_an_unpromised_api(self):
        self.repository_namespace()
        bridge = self.local_artifact("fixture-starter", self.junit("junit-jupiter-api", "compile"))
        version = json.loads((HERE / "maven-lifecycle.lock.json").read_text(encoding="utf-8"))["junit_version"]
        management = '<dependencyManagement><dependencies><dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>' + \
                     version + '</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>'
        source = '''package probe.service;
public class Service {
  /** 返回仅供校准的 JUnit 类型名称。 */
  public String read() { return org.junit.jupiter.api.Test.class.getName(); }
}'''
        self.dependencies(bridge, management)
        self.case.write("src/test-named-production/java/probe/Service.java", source)
        report = self.case.command("SG_UNDECLARED_LIBRARY")
        self.assertTrue(report["build"]["ok"])
        self.assertNotIn("junit-bom", {item["artifact"] for item in report["build"]["direct_dependencies"]})
        self.assertTrue(any(item.get("target") == "org.junit.jupiter.api.Test" for item in report["diagnostics"]))
        self.case.repaired_project()
        self.dependencies(bridge + self.junit("junit-jupiter-api", "compile"), management)
        self.case.write("src/test-named-production/java/probe/Service.java", source)
        self.case.command()

    def test_test_scope_capability_does_not_authorize_production_source(self):
        self.repository_namespace()
        bridge = self.local_artifact("bridge", self.junit("junit-jupiter-api", "compile"))
        source = '''package probe.service;
public class Service {
  /** 返回限定校准类型名称。 */
  public String read() { return org.junit.jupiter.api.Test.class.getName(); }
}'''
        self.dependencies(bridge + self.junit("junit-jupiter"))
        self.case.write("src/test-named-production/java/probe/Service.java", source)
        report = self.case.command("SG_UNDECLARED_LIBRARY")
        self.assertTrue(report["build"]["ok"], "须由真实编译成功后的声明用途检查拒绝")
        self.assertTrue(any(item.get("target") == "org.junit.jupiter.api.Test" and item.get("purpose") == "production"
                            for item in report["diagnostics"] if item["code"] == "SG_UNDECLARED_LIBRARY"))
        self.case.repaired_project()
        self.dependencies(bridge + self.junit("junit-jupiter", "compile"))
        self.case.write("src/test-named-production/java/probe/Service.java", source)
        self.case.command()

    def test_unvalidated_capability_version_is_not_misreported_as_an_invalid_coding_rule(self):
        version = "5.11.3"
        management = '<dependencyManagement><dependencies><dependency><groupId>org.junit.jupiter</groupId>' + \
                     '<artifactId>junit-jupiter-api</artifactId><version>' + version + \
                     '</version></dependency></dependencies></dependencyManagement>'
        self.dependencies(self.junit("junit-jupiter"), management)
        self.case.write("verification/java/probe/ServiceTest.java", '''package probe.service;
class ServiceTest { @org.junit.jupiter.api.Test void verify() {} }
''')
        report = self.case.command("SG_DEPENDENCY_CAPABILITY_UNCALIBRATED")
        self.assertTrue(report["build"]["ok"])
        self.assertNotIn("SG_UNDECLARED_LIBRARY", {item["code"] for item in report["diagnostics"]},
                         "已知能力的新组合是校准缺口，不能误称违反 A 决定")
        self.case.repaired_project()
        self.dependencies(self.junit("junit-jupiter"))
        self.case.command()

    def test_missing_partial_or_contradictory_direct_observation_is_rejected(self):
        import calibrate_maven
        from calibrate_source import ProbeFailure

        self.dependencies(self.junit("junit-jupiter"))
        project, _ = calibrate_maven.validate_project(self.case.project)
        with tempfile.TemporaryDirectory(prefix="domain-forge-direct-observation-") as temporary:
            document, _ = calibrate_maven.compile_project(pathlib.Path(self.case.maven), project,
                            pathlib.Path(self.case.repository), pathlib.Path(temporary))
        original = ET.tostring(document)
        expected = calibrate_maven.direct_dependencies(document)
        self.assertEqual({item["artifact"] for item in expected}, {"junit-jupiter"})
        for variant in ("missing", "missing-identity", "contradictory", "transitive-as-direct", "wrong-project"):
            with self.subTest(variant=variant):
                changed = ET.fromstring(original)
                direct = changed.find("dependencyResolution/constraint[@direct='true']")
                self.assertIsNotNone(direct)
                if variant == "missing":
                    del direct.attrib["direct"]
                elif variant == "missing-identity":
                    del direct.attrib["version"]
                elif variant == "contradictory":
                    duplicate = ET.fromstring(ET.tostring(direct))
                    duplicate.set("direct", "false")
                    changed.find("dependencyResolution").append(duplicate)
                elif variant == "transitive-as-direct":
                    changed.find("dependencyResolution/constraint[@artifact='junit-jupiter-api']").set("direct", "true")
                else:
                    changed.find("dependencyResolution").set("artifact", "another-project")
                with self.assertRaises(ProbeFailure) as caught:
                    calibrate_maven.direct_dependencies(changed)
                self.assertEqual(caught.exception.diagnostic["code"], "SG_DEPENDENCY_DECLARATION")
        self.assertEqual(original, ET.tostring(document), "反例副本不能改写原生基线")
        self.assertEqual(expected, calibrate_maven.direct_dependencies(document))

    def test_invalid_capability_map_is_rejected_before_build_then_restores(self):
        isolated = self.case.root / "isolated-checkout/tools/engineering_gate"
        shutil.copytree(HERE, isolated)
        lock = isolated / "maven-lifecycle.lock.json"
        original = lock.read_text(encoding="utf-8")
        baseline = json.loads(original)
        declaration = self.case.project / "modules.json"
        declaration.write_text(json.dumps(self.case.definition), encoding="utf-8")
        argv = [sys.executable, "-B", str(isolated / ENTRY_NAME), "--project", str(self.case.project),
                "--declaration", str(declaration), "--maven-home", self.case.maven, "--repository", self.case.repository,
                "--tools", self.case.tools]
        for variant in ("missing", "wildcard", "unknown-version", "duplicate-rule", "unknown-field", "duplicate-key"):
            with self.subTest(variant=variant):
                changed = json.loads(original)
                rule = changed["dependency_capabilities"][0]
                if variant == "missing":
                    del changed["dependency_capabilities"]
                elif variant == "wildcard":
                    rule["apis"][0]["artifact"] = "*"
                elif variant == "unknown-version":
                    rule["provider"]["version_key"] = "unknown_version"
                elif variant == "duplicate-rule":
                    changed["dependency_capabilities"].append(rule)
                elif variant == "unknown-field":
                    rule["allow_all_transitives"] = True
                text = json.dumps(changed)
                if variant == "duplicate-key":
                    text = '{"dependency_capabilities": [],' + text[1:]
                lock.write_text(text, encoding="utf-8")
                process = subprocess.run(argv, capture_output=True, text=True, timeout=30)
                self.assertEqual(process.returncode, 1, process.stdout + process.stderr)
                report = json.loads(process.stdout)
                self.assertIn(report["diagnostics"][0]["code"], {"SG_DEPENDENCY_CAPABILITY", "SG_BUILD_BASELINE"})
                self.assertFalse((self.case.project / "target").exists(), "坏配置必须在原生构建前拒绝")
        lock.write_text(original, encoding="utf-8")
        self.assertEqual(json.loads(lock.read_text(encoding="utf-8")), baseline)
        process = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertTrue(json.loads(process.stdout)["source_dependency_declarations_checked"])
        self.assertFalse(json.loads(process.stdout)["human_acceptance"])

    def test_system_path_is_not_a_supported_direct_library_then_repairs(self):
        self.vendor()
        dependency = self.local_artifact("base", classes=("Base",))
        jar = self.namespace / "base/1.0.0/base-1.0.0.jar"
        source = '''package probe.service;
public class Service {
  /** 返回供应库的校准值。 */
  public String read() { return new vendor.Base().inherited(); }
}'''
        for scope in ("system", "${fixture.sdk.scope}"):
            with self.subTest(scope=scope):
                self.dependencies(dependency.replace("</dependency>", '<scope>' + scope + '</scope><systemPath>' +
                                                     str(jar) + '</systemPath></dependency>'))
                pom = self.case.project / "pom.xml"
                pom.write_text(pom.read_text(encoding="utf-8").replace("<properties>",
                    "<properties><fixture.sdk.scope>system</fixture.sdk.scope>"), encoding="utf-8")
                self.case.write("src/test-named-production/java/probe/Service.java", source)
                report = self.case.command("SG_BUILD_DEPENDENCY_PATH")
                self.assertEqual(report["diagnostics"][0]["dependency"]["scope"], "system", "必须观察实际有效用途，不搜索 POM 字面量")
                self.assertIn("BUILD SUCCESS", (self.case.project / "target/maven-scope.log").read_text(encoding="utf-8"))
                self.case.repaired_project()
        self.dependencies(dependency)
        self.case.write("src/test-named-production/java/probe/Service.java", source)
        self.case.command()


ENTRY_NAME = "calibrate_maven_architecture.py"


class DependencyDeclarationPartitionChecks(unittest.TestCase):
    def test_fixed_groups_cover_every_case_exactly_once_and_match_ci(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module = sys.modules[__name__]
        loader = unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "依赖声明模块加载重复用例")
        selected = set()
        for check, names in (("dependency-declaration-tests", ["DependencyDeclarationEntrySuite", "DependencyDeclarationPartitionChecks"]),
                             ("dependency-declaration-source-tests", ["DependencyDeclarationSourceSuite"]),
                             ("dependency-declaration-input-tests", ["DependencyDeclarationInputSuite"])):
            self.assertIn(check, catalog["checks"], "依赖声明固定分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "tools/engineering_gate/test_dependency_declarations.py", *names],
                             "依赖声明固定分组命令偏离登记合同")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"],
                             ["@python", "-B", "tools/engineering_gate/calibrate_maven_architecture.py", "--environment"],
                             "依赖声明环境预检偏离固定入口")
            command = "        run: python -B tools/engineering_gate/test_dependency_declarations.py " + " ".join(names)
            self.assertEqual(workflow.splitlines().count(command), 1, "依赖声明 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group, "依赖声明固定分组不能为空")
            self.assertEqual(len(group), len(set(group)), "依赖声明组内重复用例")
            self.assertFalse(selected & set(group), "依赖声明跨组重复用例")
            selected.update(group)
        self.assertEqual(selected, set(complete), "依赖声明固定分组遗漏用例")


def DependencyDeclarationEntrySuite():
    names = ("test_accidental_transitive_library_requires_direct_declaration_then_repairs",
             "test_official_aggregator_api_is_not_repeated_as_a_direct_dependency",
             "test_unvalidated_capability_version_is_not_misreported_as_an_invalid_coding_rule")
    return unittest.defaultTestLoader.loadTestsFromNames(["DependencyDeclarationCommands." + name for name in names], sys.modules[__name__])


def DependencyDeclarationSourceSuite():
    names = ("test_source_retention_annotation_and_inlined_constant_cannot_disappear",
             "test_sdk_inherited_implementation_owner_does_not_force_duplicate_declaration",
             "test_bom_and_starter_name_do_not_authorize_an_unpromised_api")
    return unittest.defaultTestLoader.loadTestsFromNames(["DependencyDeclarationCommands." + name for name in names], sys.modules[__name__])


def DependencyDeclarationInputSuite():
    names = ("test_test_scope_capability_does_not_authorize_production_source",
             "test_missing_partial_or_contradictory_direct_observation_is_rejected",
             "test_invalid_capability_map_is_rejected_before_build_then_restores",
             "test_system_path_is_not_a_supported_direct_library_then_repairs")
    return unittest.defaultTestLoader.loadTestsFromNames(["DependencyDeclarationCommands." + name for name in names], sys.modules[__name__])


if __name__ == "__main__":
    unittest.main()
