"""从真实 Wrapper、JUnit 与 JAR 结果校准门禁，不以手写执行报告代替。"""

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from test_maven_scope import MavenScopeFixtures


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_lifecycle.py"


class MavenLifecycleFixtures(MavenScopeFixtures):
    """在无输出的独立目录准备官方脚本和真实测试，整改不删除失败现场。"""

    def setUp(self):
        super().setUp()
        self.scope = json.loads((HERE / "maven-scope.lock.json").read_text(encoding="utf-8"))
        self.lifecycle = json.loads((HERE / "maven-lifecycle.lock.json").read_text(encoding="utf-8"))
        self.archive = os.environ.get("DOMAIN_FORGE_GATE_WRAPPER", "")
        self.assertTrue(self.archive, "必须提供固定官方 Wrapper，不允许跳过")
        self.assertEqual(hashlib.sha256(pathlib.Path(self.archive).read_bytes()).hexdigest(), self.scope["wrapper"]["sha256"])
        self.contract = self.root / "required-tests.json"
        self.contract.write_text(json.dumps({"schema": 1, "required_tests": [
            "probe.ServiceTest#readsValue", "probe.ServiceTest#rejectsWrongExpectation"]}), encoding="utf-8")
        self.lifecycle_project()

    def lifecycle_project(self):
        with zipfile.ZipFile(self.archive) as archive:
            for name in ("mvnw", "mvnw.cmd"):
                (self.project / name).write_bytes(archive.read(name))
        (self.project / "mvnw").chmod(0o755)
        self.write(".mvn/wrapper/maven-wrapper.properties", "\n".join([
            "wrapperVersion=" + self.scope["wrapper"]["version"], "distributionType=only-script",
            "distributionUrl=" + self.scope["url"], "distributionSha256Sum=" + self.scope["distribution_sha256"]]))
        self.pom()
        pom = self.project / "pom.xml"
        text = pom.read_text(encoding="utf-8").replace("<build>", '''<dependencies><dependency>
<groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>''' + self.lifecycle["junit_version"] + '''</version><scope>test</scope>
</dependency></dependencies><build>''').replace("</plugins>", '''<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>''' + self.lifecycle["surefire_version"] + '''</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>''' + self.lifecycle["jar_version"] + '''</version></plugin></plugins>''')
        pom.write_text(text, encoding="utf-8")
        self.write("verification/java/probe/ServiceTest.java", '''package probe;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ServiceTest {
  @Test void readsValue() { assertEquals("ok", new Service().read()); }
  @Test void rejectsWrongExpectation() { assertNotEquals("unexpected", new Service().read()); }
}''')

    def repair(self):
        self.repaired_project()
        self.lifecycle_project()

    def replace(self, relative, old, new):
        path = self.project / relative
        original = path.read_text(encoding="utf-8")
        self.assertIn(old, original, "反例必须确实改变目标输入")
        path.write_text(original.replace(old, new), encoding="utf-8")

    def plugin_configuration(self, name, value):
        self.replace("pom.xml", "<artifactId>maven-" + name + "-plugin</artifactId>",
                     "<artifactId>maven-" + name + "-plugin</artifactId><configuration>" + value + "</configuration>")

    def command(self, diagnostic=None, **overrides):
        values = dict(project=str(self.project), contract=str(self.contract), maven_home=self.maven,
                      repository=self.repository, wrapper=self.archive)
        values.update(overrides)
        argv = [sys.executable, "-B", str(ENTRY)]
        for key, value in values.items():
            argv.extend(["--" + key.replace("_", "-"), value])
        result = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
        self.assertTrue(result.stdout.strip(), "公开入口必须返回可定位的真实证据")
        report = json.loads(result.stdout)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], report)
        return report


class MavenLifecycleExecutionCommands(MavenLifecycleFixtures):
    def test_actual_verify_observes_tests_versions_and_packaged_classes(self):
        self.replace("verification/java/probe/ServiceTest.java", "class ServiceTest {", '''class ServiceTest {
  @Test void additionalLegalCase() { assertTrue(new Service().read().startsWith("o")); }''')
        before = {str(path): path.read_bytes() for path in self.project.rglob("*") if path.is_file()}
        report = self.command()
        self.assertEqual(report["proof_scope"], "isolated-wrapper-verify-lifecycle-only")
        self.assertEqual(report["command"][0], str(self.project / "mvnw"))
        self.assertEqual(report["command"][-1], "verify")
        self.assertEqual(report["exit_code"], 0)
        self.assertEqual(set(report["tests"]["executed"]), {"probe.ServiceTest#readsValue", "probe.ServiceTest#rejectsWrongExpectation", "probe.ServiceTest#additionalLegalCase"})
        self.assertEqual(report["tests"]["count"], 3)
        self.assertTrue(any(item["artifact"] == "junit-platform-engine" for item in report["resolved_dependencies"]))
        self.assertTrue(any(item["artifact"] == "junit-jupiter" and item["scope"] == "test" for item in report["resolved_dependencies"]))
        self.assertTrue(report["resolved_plugin_dependencies"])
        self.assertTrue(report["resolved_project_constraints"])
        self.assertTrue(report["resolved_plugin_constraints"])
        self.assertEqual(report["distribution_core_sha256"], self.scope["core_sha256"])
        self.assertEqual(before, {str(path): path.read_bytes() for path in self.project.rglob("*")
                                 if path.is_file() and not path.is_relative_to(self.project / "target")})
        artifact = pathlib.Path(report["artifact"]["path"])
        self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), report["artifact"]["sha256"])
        with zipfile.ZipFile(artifact) as archive:
            self.assertIn("probe/Service.class", archive.namelist())
            self.assertNotIn("probe/ServiceTest.class", archive.namelist())
        for field in ("product_build_proven", "publication_proven", "human_acceptance"):
            self.assertFalse(report[field])

    def test_skipping_tests_is_native_success_but_gate_failure_then_repaired(self):
        self.plugin_configuration("surefire", "<skipTests>true</skipTests>")
        report = self.command("SG_BUILD_TESTS")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0, "不能以任意 Maven 失败证明跳过拦截")
        self.assertIn("Tests are skipped", pathlib.Path(report["diagnostics"][0]["log"]).read_text(encoding="utf-8"))
        self.repair()
        self.command()

    def test_real_assertion_failure_and_compile_error_are_native_failures(self):
        self.replace("verification/java/probe/ServiceTest.java", 'assertEquals("ok",', 'assertEquals("wrong",')
        report = self.command("SG_MAVEN_FAILURE")
        self.assertNotEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertIn("AssertionFailedError", pathlib.Path(report["diagnostics"][0]["log"]).read_text(encoding="utf-8"))
        self.repair()
        self.replace("src/test-named-production/java/probe/Service.java", 'return "ok";', 'invalid java;')
        report = self.command("SG_MAVEN_FAILURE")
        self.assertNotEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertIn("COMPILATION ERROR", pathlib.Path(report["diagnostics"][0]["log"]).read_text(encoding="utf-8"))
        self.repair()
        self.command()


class MavenLifecycleTestCoverageCommands(MavenLifecycleFixtures):
    def test_compiled_but_unselected_required_test_cannot_hide_behind_nonempty_results(self):
        self.plugin_configuration("surefire", "<test>ServiceTest#readsValue</test>")
        report = self.command("SG_BUILD_TEST_COVERAGE")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertEqual(report["diagnostics"][0]["executed"], ["probe.ServiceTest#readsValue"])
        self.assertEqual(report["diagnostics"][0]["missing"], ["probe.ServiceTest#rejectsWrongExpectation"])
        self.repair()
        self.command()

    def test_disabled_case_and_no_discovery_do_not_pass_as_success(self):
        self.replace("verification/java/probe/ServiceTest.java", "@Test void readsValue()", "@org.junit.jupiter.api.Disabled @Test void readsValue()")
        report = self.command("SG_BUILD_TESTS")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.repair()
        self.replace("verification/java/probe/ServiceTest.java", "@Test", "")
        report = self.command("SG_BUILD_TESTS")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0, "默认无测试成功必须被门禁识别")
        self.repair()
        self.command()

    def test_skip_test_compilation_and_ignored_failure_are_not_passed(self):
        self.replace("pom.xml", "<properties>", "<properties><maven.test.skip>true</maven.test.skip>")
        report = self.command("SG_SOURCE_COVERAGE")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.repair()
        self.plugin_configuration("surefire", "<testFailureIgnore>true</testFailureIgnore>")
        self.replace("verification/java/probe/ServiceTest.java", 'assertEquals("ok",', 'assertEquals("wrong",')
        report = self.command("SG_BUILD_TESTS")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.repair()
        self.command()


class MavenLifecycleArtifactCommands(MavenLifecycleFixtures):
    def test_required_test_or_package_goal_removed_from_lifecycle_is_rejected(self):
        for name, execution in (("surefire", "default-test"), ("jar", "default-jar")):
            with self.subTest(plugin=name):
                self.replace("pom.xml", "<artifactId>maven-" + name + "-plugin</artifactId>",
                             "<artifactId>maven-" + name + "-plugin</artifactId><executions><execution><id>" +
                             execution + "</id><phase>none</phase></execution></executions>")
                report = self.command("SG_BUILD_LIFECYCLE")
                self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
                self.assertIn(name, report["diagnostics"][0]["message"])
                self.repair()
        self.command()

    def test_successful_package_with_missing_production_class_is_rejected_then_repaired(self):
        self.write("src/test-named-production/java/probe/Marker.java", "package probe; class Marker {}")
        self.plugin_configuration("jar", "<excludes><exclude>probe/Service.class</exclude></excludes>")
        report = self.command("SG_BUILD_ARTIFACT")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertEqual(report["diagnostics"][0]["missing"], ["probe/Service.class"])
        with zipfile.ZipFile(self.project / "target/scope-1.0.0.jar") as archive:
            self.assertIn("probe/Marker.class", archive.namelist(), "反例是部分产物遗漏，不是整个夹具未构建")
        self.repair()
        self.command()


class MavenLifecycleVersionCommands(MavenLifecycleFixtures):
    def test_effective_range_is_rejected_even_when_actual_resolution_is_fixed(self):
        self.replace("pom.xml", "<version>" + self.lifecycle["junit_version"] + "</version>",
                     "<version>[" + self.lifecycle["junit_version"] + "]</version>")
        report = self.command("SG_BUILD_VERSION")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertTrue(any(item["version"] == "[" + self.lifecycle["junit_version"] + "]" for item in report["diagnostics"][0]["invalid"]))
        self.assertTrue(any(item["artifact"] == "junit-jupiter" and item["version"] == self.lifecycle["junit_version"]
                            for item in report["diagnostics"][0]["resolved_dependencies"]))
        self.repair()
        self.command()

    def test_actual_bytecode_target_must_match_calibrated_java_not_only_runtime(self):
        self.replace("pom.xml", "<maven.compiler.release>21</maven.compiler.release>", "<maven.compiler.release>17</maven.compiler.release>")
        report = self.command("SG_BUILD_BASELINE")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertIn("字节码", report["diagnostics"][0]["message"])
        self.repair()
        self.command()

    def test_actual_success_with_wrong_mandatory_plugin_version_is_rejected(self):
        self.replace("pom.xml", "<version>" + self.lifecycle["jar_version"] + "</version>", "<version>3.4.1</version>")
        report = self.command("SG_BUILD_BASELINE")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertEqual(report["diagnostics"][0]["expected"], self.lifecycle["jar_version"])
        self.assertTrue(all(item["version"] == "3.4.1" for item in report["diagnostics"][0]["observed"]))
        self.repair()
        self.command()


class MavenLifecycleResolutionCommands(MavenLifecycleFixtures):
    def local_dependency_graph(self, version, constraint):
        namespace = tempfile.TemporaryDirectory(prefix="domainforge-lifecycle-", dir=self.repository)
        self.addCleanup(namespace.cleanup)
        group = pathlib.Path(namespace.name).name
        for artifact, selected, dependencies in (("leaf", version, ""), ("bridge", "1.0.0", '''<dependencies><dependency>
<groupId>''' + group + '''</groupId><artifactId>leaf</artifactId><version>''' + constraint + '''</version>
</dependency></dependencies>''')):
            folder = pathlib.Path(namespace.name) / artifact / selected
            folder.mkdir(parents=True)
            with zipfile.ZipFile(folder / (artifact + "-" + selected + ".jar"), "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n\n")
            (folder / (artifact + "-" + selected + ".pom")).write_text('''<project><modelVersion>4.0.0</modelVersion><groupId>''' + group +
                "</groupId><artifactId>" + artifact + "</artifactId><version>" + selected + "</version>" + dependencies + "</project>", encoding="utf-8")
        if "[" in constraint:
            (pathlib.Path(namespace.name) / "leaf/maven-metadata-local.xml").write_text('''<metadata>
<groupId>''' + group + '''</groupId><artifactId>leaf</artifactId><versioning><release>''' + version + '''</release>
<versions><version>''' + version + '''</version></versions><lastUpdated>20261011000000</lastUpdated></versioning></metadata>''', encoding="utf-8")
        return group

    def test_transitive_snapshot_is_observed_from_real_maven_resolution(self):
        group = self.local_dependency_graph("1.0-SNAPSHOT", "1.0-SNAPSHOT")
        self.replace("pom.xml", "<dependencies>", "<dependencies><dependency><groupId>" + group +
                     "</groupId><artifactId>bridge</artifactId><version>1.0.0</version><scope>test</scope></dependency>")
        report = self.command("SG_BUILD_VERSION")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertTrue(any(item["group"] == group and item["artifact"] == "leaf" and item["baseVersion"] == "1.0-SNAPSHOT"
                            for item in report["diagnostics"][0]["invalid"]), report)
        self.repair()
        self.command()

    def test_transitive_version_range_cannot_hide_behind_a_fixed_resolved_version(self):
        group = self.local_dependency_graph("1.0.0", "[1.0,2.0)")
        self.replace("pom.xml", "<dependencies>", "<dependencies><dependency><groupId>" + group +
                     "</groupId><artifactId>bridge</artifactId><version>1.0.0</version><scope>test</scope></dependency>")
        report = self.command("SG_BUILD_VERSION")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0, "必须是原生成功解析范围后被门禁拒绝")
        self.assertTrue(any(item["group"] == group and item["artifact"] == "leaf" and
                            item["version"] == "1.0.0" and item.get("constraint") == "[1.0,2.0)"
                            for item in report["diagnostics"][0]["invalid"]), report)
        self.repair()
        self.command()

    def test_plugin_transitive_range_is_checked_independently_of_project_dependencies(self):
        group = self.local_dependency_graph("1.0.0", "[1.0,2.0)")
        self.replace("pom.xml", "<artifactId>maven-jar-plugin</artifactId>",
                     "<artifactId>maven-jar-plugin</artifactId><dependencies><dependency><groupId>" + group +
                     "</groupId><artifactId>bridge</artifactId><version>1.0.0</version></dependency></dependencies>")
        report = self.command("SG_BUILD_VERSION")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0, "插件实际成功解析范围后仍须被拒绝")
        self.assertTrue(any(item["group"] == group and item["artifact"] == "leaf" and
                            item["version"] == "1.0.0" and item.get("constraint") == "[1.0,2.0)"
                            for item in report["diagnostics"][0]["invalid"]), report)
        self.repair()
        self.command()

    def test_system_dependency_is_rejected_after_native_verify_and_repairs(self):
        group = self.local_dependency_graph("1.0.0", "1.0.0")
        jar = pathlib.Path(self.repository) / group / "leaf/1.0.0/leaf-1.0.0.jar"
        self.replace("pom.xml", "<properties>", "<properties><fixture.sdk.scope>system</fixture.sdk.scope>" +
                     "<fixture.sdk.path>" + str(jar) + "</fixture.sdk.path>")
        self.replace("pom.xml", "<dependencies>", '''<dependencies><dependency><groupId>''' + group + '''</groupId>
<artifactId>leaf</artifactId><version>1.0.0</version><scope>${fixture.sdk.scope}</scope>
<systemPath>${fixture.sdk.path}</systemPath></dependency>''')
        report = self.command("SG_BUILD_DEPENDENCY_PATH")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0, "目标拒绝不能由原生编译或测试失败冒充")
        self.assertEqual(report["diagnostics"][0]["dependency"]["scope"], "system")
        log = pathlib.Path(report["diagnostics"][0]["log"]).read_text(encoding="utf-8")
        self.assertIn("BUILD SUCCESS", log)
        self.assertIn("Tests run: 2", log)
        self.repair()
        self.replace("pom.xml", "<dependencies>", "<dependencies><dependency><groupId>" + group +
                     "</groupId><artifactId>leaf</artifactId><version>1.0.0</version></dependency>")
        repaired = self.command()
        self.assertTrue(any(item["group"] == group and item["artifact"] == "leaf" and item["scope"] == "compile"
                            for item in repaired["resolved_dependencies"]), "正常坐标声明须在新工程中实际解析，不能靠删除依赖整改")


class MavenLifecycleInputCommands(MavenLifecycleFixtures):
    def test_native_constraint_inventory_cannot_be_missing_partial_or_for_another_project(self):
        from calibrate_lifecycle import versions_and_artifacts
        from calibrate_source import ProbeFailure
        report = self.command()
        original = pathlib.Path(report["inventory"]["path"]).read_bytes()
        versions_and_artifacts(ET.fromstring(original), pathlib.Path(self.repository))
        for change in ("missing", "partial", "wrong-project", "missing-plugin", "partial-plugin"):
            document = ET.fromstring(original)
            if change in ("missing-plugin", "partial-plugin"):
                for node in document.findall("pluginConstraint"):
                    if change == "missing-plugin" or node.attrib["artifact"] == "maven-resources-plugin":
                        document.remove(node)
            for resolution in document.findall("dependencyResolution"):
                if change == "missing":
                    document.remove(resolution)
                elif change == "wrong-project":
                    resolution.attrib["artifact"] = "other-project"
                elif change == "partial":
                    for node in resolution.findall("constraint"):
                        if node.attrib["artifact"] == "junit-jupiter-api":
                            resolution.remove(node)
            with self.subTest(change=change), self.assertRaises(ProbeFailure) as failure:
                versions_and_artifacts(document, pathlib.Path(self.repository))
            self.assertEqual(failure.exception.diagnostic["code"], "SG_BUILD_RESOLUTION")
        self.assertEqual(pathlib.Path(report["inventory"]["path"]).read_bytes(), original,
                         "隔离内存反例不能改写或冒充原生执行证据")

    def test_missing_empty_duplicate_and_unknown_contract_are_rejected_before_build(self):
        self.command("SG_BUILD_CONTRACT", contract=str(self.root / "missing.json"))
        original = self.contract.read_bytes()
        for value in ({"schema": 1, "required_tests": []}, {"schema": 1, "required_tests": ["a#b", "a#b"]},
                      {"schema": True, "required_tests": ["a#b"]}, {"schema": 1, "required_tests": ["a#b"], "skip": True}):
            self.contract.write_text(json.dumps(value), encoding="utf-8")
            self.command("SG_BUILD_CONTRACT")
            self.assertFalse((self.project / "target").exists())
        self.contract.write_text('{"schema":2,"schema":1,"required_tests":["a#b"]}', encoding="utf-8")
        self.command("SG_BUILD_CONTRACT")
        self.contract.write_bytes(original)
        self.command()

    def test_stale_test_reports_are_preserved_and_rejected_before_running(self):
        stale = self.write("target/surefire-reports/TEST-forged.xml", '<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        before = stale.read_bytes()
        self.command("SG_DIRTY_BUILD")
        self.assertEqual(stale.read_bytes(), before)
        self.assertFalse((self.project / "target/maven-lifecycle.log").exists())
        self.repair()
        self.command()

    def test_native_success_that_changes_maintained_source_invalidates_evidence(self):
        self.replace("verification/java/probe/ServiceTest.java", "void readsValue() {", '''void readsValue() throws Exception {
    java.nio.file.Files.writeString(java.nio.file.Path.of("src/test-named-production/java/probe/Service.java"),
        "\\n", java.nio.file.StandardOpenOption.APPEND);''')
        report = self.command("SG_INPUT_CHANGED")
        self.assertEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertTrue((self.project / "src/test-named-production/java/probe/Service.java").read_bytes().endswith(b"\n\n"))
        self.repair()
        self.command()

    def test_version_policy_is_narrow_and_cannot_accept_floating_spellings(self):
        from calibrate_lifecycle import fixed_version
        for value in ("LATEST", "RELEASE", "latest", "1.0-SNAPSHOT", "[1.0,2.0)", "[1.0]", "1.+", "1.*", "${unresolved}", "", None):
            self.assertFalse(fixed_version(value), value)
        for value in ("1.0.0", "2.0.0.Final", "3.5.4", "1.2.3-patch1"):
            self.assertTrue(fixed_version(value), value)


class MavenLifecyclePartitionChecks(unittest.TestCase):
    """固定语义分组覆盖所有用例，并核对本地与 CI 选择，不扩大执行预算。"""

    def test_fixed_groups_cover_all_lifecycle_tests_exactly_once(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [name for item in suite for name in identifiers(item)]

        module = sys.modules[__name__]
        loader = unittest.TestLoader()
        complete = set(identifiers(loader.loadTestsFromModule(module)))
        selected = set()
        groups = (("build-mechanism", ["MavenLifecycleExecutionCommands", "MavenLifecyclePartitionChecks"]),
                  ("build-test-coverage-tests", ["MavenLifecycleTestCoverageCommands"]),
                  ("build-artifact-tests", ["MavenLifecycleArtifactCommands"]),
                  ("build-version-tests", ["MavenLifecycleVersionCommands"]),
                  ("build-resolution-tests", ["MavenLifecycleResolutionCommands"]),
                  ("build-input-tests", ["MavenLifecycleInputCommands"]))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        for check, names in groups:
            self.assertIn(check, catalog["checks"], "生命周期分组缺少登记")
            self.assertTrue(catalog["checks"][check]["implemented"], "生命周期责任不能只填写名称")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "harness/engineering_gate/test_maven_lifecycle.py", *names],
                             "生命周期固定分组命令偏离")
            self.assertEqual(catalog["checks"][check]["environment_argv"],
                             ["@python", "-B", "harness/engineering_gate/calibrate_lifecycle.py", "--environment"], "生命周期环境检查遗漏")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertIn("run: python -B harness/engineering_gate/test_maven_lifecycle.py " + " ".join(names), workflow,
                          "CI 与本地生命周期分组不一致")
            cases = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(cases)
            self.assertEqual(len(cases), len(set(cases)), "分组内重复不能被集合吞掉")
            self.assertFalse(selected.intersection(cases), "用例不能跨组重复")
            selected.update(cases)
        self.assertEqual(selected, complete, "固定分组不能漏掉新用例")


if __name__ == "__main__":
    unittest.main()
