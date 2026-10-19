"""真实 Maven 编译范围反例；不以手写报告或路径名称证明生产用途。"""

import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_maven.py"


class MavenScopeFixtures(unittest.TestCase):
    """共享真实 Maven 隔离工程，不携带测试或推定源码用途。"""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="domain-forge-maven-scope-test-")
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name).resolve()
        self.maven = os.environ.get("DOMAIN_FORGE_GATE_MAVEN", "")
        self.repository = os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY", "")
        self.assertTrue(self.maven and self.repository, "必须提供真实固定 Maven 与专用依赖目录，不能跳过")
        self.project = self.root / "project"
        self.repair_count = 0
        self.write("src/test-named-production/java/probe/Service.java",
                   'package probe; /** 提供编译范围校准的简单服务。 */ public class Service { public String read() { return "ok"; } }')
        self.write("verification/java/probe/ServiceTest.java",
                   'package probe; class ServiceTest { String verify() { return new Service().read(); } }')
        self.pom()

    def write(self, relative, value):
        path = self.project / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value + "\n", encoding="utf-8")
        return path

    def pom(self, configuration=""):
        versions = json.loads((HERE / "maven-scope.lock.json").read_text(encoding="utf-8"))
        self.write("pom.xml", '''<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion><groupId>probe</groupId><artifactId>scope</artifactId><version>1.0.0</version>
  <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
  <build><sourceDirectory>src/test-named-production/java</sourceDirectory><testSourceDirectory>verification/java</testSourceDirectory>
    <plugins>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>''' + versions["resources_version"] + '''</version></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>''' + versions["compiler_version"] + '''</version>
        <configuration>''' + configuration + '''</configuration>
      </plugin>
    </plugins>
  </build>
</project>''')

    def command(self, diagnostic=None):
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project),
                                 "--maven-home", self.maven, "--repository", self.repository],
                                capture_output=True, text=True, timeout=30)
        self.assertIn(result.returncode, (0, 1), "真实 Maven 范围入口尚未提供目标行为：" + result.stdout + result.stderr)
        self.assertTrue(result.stdout.strip(), "真实 Maven 范围入口尚未返回实际编译证据：" + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, report)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], report)
        return report

    def repaired_project(self):
        self.repair_count += 1
        self.project = self.root / ("repaired-" + str(self.repair_count))
        self.write("src/test-named-production/java/probe/Service.java",
                   'package probe; /** 提供编译范围校准的简单服务。 */ public class Service { public String read() { return "ok"; } }')
        self.write("verification/java/probe/ServiceTest.java",
                   'package probe; class ServiceTest { String verify() { return new Service().read(); } }')
        self.pom()


class MavenScopeCommands(MavenScopeFixtures):
    """每次使用无构建输出的独立工程，失败后用新工程验证整改。"""

    def test_actual_compile_purpose_not_test_named_path_and_all_created_classes(self):
        self.write("src/test-named-production/java/probe/Service.java", '''package probe;
public class Service {
  public String read() {
    class Local { String value() { return "ok"; } }
    Runnable callback = new Runnable() { public void run() {} };
    callback.run();
    return new Local().value();
  }
}''')
        report = self.command()
        self.assertEqual(report["proof_scope"], "observed-maven-compilation-scope-only")
        self.assertFalse(report["semantic_purpose_proven"])
        self.assertFalse(report["publication_proven"])
        self.assertFalse(report["human_acceptance"])
        scopes = {item["purpose"]: item for item in report["compilations"]}
        self.assertEqual([pathlib.Path(path).name for path in scopes["production"]["sources"]], ["Service.java"])
        self.assertEqual([pathlib.Path(path).name for path in scopes["test"]["sources"]], ["ServiceTest.java"])
        self.assertEqual({pathlib.Path(path).name for path in scopes["production"]["classes"]},
                         {"Service.class", "Service$1Local.class", "Service$1.class"})
        self.assertTrue(all(path in report["inputs"] for item in report["compilations"] for path in item["sources"]))

    def test_explicit_resource_directory_does_not_change_default_build_output(self):
        pom = self.project / "pom.xml"
        content = pom.read_text(encoding="utf-8").replace("</plugins>",
                    "</plugins><resources><resource><directory>src/main/resources</directory></resource></resources>")
        pom.write_text(content, encoding="utf-8")
        self.write("src/main/resources/message.txt", "fixture resource")
        report = self.command()
        self.assertEqual((self.project / "target/classes/message.txt").read_text(encoding="utf-8"), "fixture resource\n")
        self.assertEqual({item["purpose"] for item in report["compilations"]}, {"production", "test"})
        for tag in ("directory", "outputDirectory", "testOutputDirectory"):
            with self.subTest(unsupported_output=tag):
                self.repaired_project()
                pom = self.project / "pom.xml"
                pom.write_text(pom.read_text(encoding="utf-8").replace("<build>", "<build><" + tag + ">custom-output</" + tag + ">"), encoding="utf-8")
                self.command("SG_BUILD_SCOPE")

    def test_excluded_maintained_source_and_skipped_compilation_cannot_pass(self):
        self.write("src/test-named-production/java/probe/Excluded.java", "package probe; class Excluded {}")
        self.pom("<excludes><exclude>**/Excluded.java</exclude></excludes>")
        report = self.command("SG_SOURCE_COVERAGE")
        self.assertIn(str(self.project / "src/test-named-production/java/probe/Excluded.java"), report["diagnostics"][0]["missing"])
        self.repaired_project()
        self.pom("<skipMain>true</skipMain>")
        self.write("verification/java/probe/ServiceTest.java", 'package probe; class ServiceTest { String verify() { return "ok"; } }')
        self.command("SG_SOURCE_COVERAGE")
        self.repaired_project()
        self.command()

    def test_unknown_source_build_error_and_preexisting_output_are_not_success(self):
        self.write("unassigned/Unknown.java", "package unassigned; class Unknown {}")
        report = self.command("SG_UNKNOWN_SOURCE")
        self.assertEqual(report["diagnostics"][0]["missing"], [str(self.project / "unassigned/Unknown.java")])
        self.repaired_project()
        self.write("src/test-named-production/java/probe/Service.java", "package probe; public class Service { invalid }")
        report = self.command("SG_MAVEN_FAILURE")
        self.assertNotEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertIn("Service.java", pathlib.Path(report["diagnostics"][0]["log"]).read_text(encoding="utf-8"))
        self.repaired_project()
        self.write("target/classes/Stale.class", "not an actual fresh compilation")
        self.command("SG_DIRTY_BUILD")

    def test_missing_runtime_and_symlink_input_are_rejected_without_skipping(self):
        self.maven = str(self.root / "missing-runtime")
        self.command("SG_MAVEN_RUNTIME")
        self.maven = os.environ["DOMAIN_FORGE_GATE_MAVEN"]
        self.write("alias.java", "package other; class Other {}")
        path = self.project / "src/test-named-production/java/probe/Alias.java"
        path.symlink_to(self.project / "alias.java")
        self.command("SG_BUILD_INPUT")

    def test_uncompiled_class_in_resources_is_not_missing_from_output_coverage(self):
        self.write("src/main/resources/probe/Unexpected.class", "not created by the compiler")
        report = self.command("SG_BYTECODE_COVERAGE")
        self.assertIn(str(self.project / "target/classes/probe/Unexpected.class"), report["diagnostics"][0]["missing"])
        self.repaired_project()
        self.command()

    def test_preparation_refuses_existing_files_without_overwriting(self):
        original = self.project / "pom.xml"
        before = original.read_bytes()
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--prepare", str(self.project)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)["diagnostics"][0]["code"], "SG_BUILD_INPUT")
        self.assertEqual(original.read_bytes(), before)
        self.assertFalse((self.project / "maven.zip").exists())

    def test_fresh_preparation_satisfies_the_downstream_environment(self):
        prepared = self.root / "fresh-runtime"
        prepared.mkdir()
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--prepare", str(prepared)],
                                capture_output=True, text=True, timeout=40)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        report = json.loads(result.stdout)
        argv = [sys.executable, "-B", str(ENTRY), "--environment",
                "--maven-home", report["maven_home"], "--repository", report["repository"]]
        checked = subprocess.run(argv, capture_output=True, text=True, timeout=10)
        self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)
        environment = json.loads(checked.stdout)["environment"]
        self.assertEqual(set(environment["plugins"]), {"compiler", "resources"})
        version = environment["plugins"]["compiler"]["version"]
        artifact = pathlib.Path(report["repository"]) / "org/apache/maven/plugins/maven-compiler-plugin" / version / ("maven-compiler-plugin-" + version + ".jar")
        artifact.unlink()
        missing = subprocess.run(argv, capture_output=True, text=True, timeout=10)
        self.assertEqual(missing.returncode, 1, missing.stdout + missing.stderr)
        self.assertEqual(json.loads(missing.stdout)["diagnostics"][0]["code"], "SG_MAVEN_RUNTIME")

    def test_observer_itself_passes_actual_source_checks(self):
        source = HERE / "MavenScopeSpy.java"
        tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(tools, "必须提供固定源码工具，不能跳过观察器质量检查")
        for action in ("comments", "method-presence", "format", "complexity"):
            argv = [sys.executable, "-B", str(HERE / "calibrate_source.py"), "--tools", tools, action]
            if action in ("comments", "complexity"):
                argv.extend(["--purpose", "production"])
            result = subprocess.run([*argv, str(source)], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
