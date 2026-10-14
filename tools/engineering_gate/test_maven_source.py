"""真实编译用途驱动源码检查；不接受自报 test、文件清单或历史构建报告。"""

import json
import os
import pathlib
import subprocess
import sys
import unittest

from test_maven_scope import MavenScopeFixtures


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_maven_source.py"


class MavenSourceCommands(MavenScopeFixtures):
    """从公开联合入口观察实际规则失败，而不是手写用途报告。"""

    def setUp(self):
        super().setUp()
        self.entry = ENTRY
        self.tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(self.tools, "需要真实固定源码工具，不能跳过")
        self.write("src/test-named-production/java/probe/Service.java", '''package probe;

/** 提供隔离校准所需的固定结果。 */
class Service {
  String read() {
    return "ok";
  }
}''')
        self.write("verification/java/probe/ServiceTest.java", '''package probe;

class ServiceTest {
  String verify() {
    return new Service().read();
  }
}''')

    def command(self, failing=False, *extra):
        process = subprocess.run([sys.executable, "-B", str(self.entry), "--project", str(self.project),
                                  "--maven-home", self.maven, "--repository", self.repository,
                                  "--tools", self.tools, *extra], capture_output=True, text=True, timeout=30)
        self.assertIn(process.returncode, (0, 1), process.stdout + process.stderr)
        report = json.loads(process.stdout)
        self.assertEqual(process.returncode, 1 if failing else 0, report)
        return report

    def test_production_type_rules_follow_actual_compilation_not_name_or_visibility(self):
        service = self.write("src/test-named-production/java/probe/Service.java", '''package probe;

public class Service {
  String read() {
    class Local {}
    return "ok";
  }
}

class Hidden {}

@Deprecated
/** 提供错位注释反例，不应被生产检查放行。 */
class Misplaced {}''')
        test = self.write("verification/java/probe/ServiceTest.java", '''package probe;

@Deprecated
/** 已采用的测试注释也必须位于全部注解之前。 */
class ServiceTest {}''')
        report = self.command(True)
        diagnostics = report["diagnostics"]
        for line in (3, 5, 10):
            self.assertTrue(any(item.get("file") == str(service) and item.get("line") == line and
                                item.get("rule") == "MissingJavadocType" and item.get("purpose") == "production"
                                for item in diagnostics), diagnostics)
        for path in (service, test):
            self.assertTrue(any(item.get("file") == str(path) and item.get("rule") == "InvalidJavadocPosition"
                                for item in diagnostics), diagnostics)
        self.assertFalse(report["source_gate_proven"])
        self.assertFalse(report["human_acceptance"])

    def test_mechanically_valid_sources_include_same_package_tests_without_claiming_method_contracts(self):
        service = self.write("src/test-named-production/java/probe/Service.java", '''package probe;

/** 提供隔离校准所需的固定结果。 */
class Service {
  /** 执行无操作回调并返回本次调用的固定值。 */
  String read() {
    /** 提供本次调用内的固定值。 */
    class Local {
      /** 本次调用使用的固定返回值。 */
      private final String result = "ok";

      String value() {
        return result;
      }
    }
    Runnable callback =
        new Runnable() {
          @Override
          public void run() {}
        };
    callback.run();
    return new Local().value();
  }
}''')
        declaration = self.write("src/test-named-production/java/probe/ReadView.java", '''package probe;

/** 提供只有声明而没有显式实现的读取合同。 */
interface ReadView {
  /** 返回当前读取值，具体前提与失败语义由实现合同确定。 */
  String read();
}''')
        report = self.command()
        self.assertEqual(report["proof_scope"], "observed-maven-source-subset-only")
        self.assertTrue(report["build_purpose_bound"])
        for flag in ("source_gate_proven", "module_ownership_proven", "contract_selection_proven",
                     "semantic_review_proven", "human_acceptance", "publication_proven"):
            self.assertFalse(report[flag])
        groups = {item["purpose"]: item for item in report["compilation_checks"]}
        self.assertEqual(set(groups), {"production", "test"})
        self.assertEqual(groups["production"]["without_explicit_implementations"], [str(declaration)])
        for group in groups.values():
            checks = {item["action"]: item for item in group["checks"]}
            self.assertEqual(set(checks), {"comments", "format", "complexity"})
            for action in ("comments", "format"):
                self.assertEqual(set(checks[action]["scanned"]), set(group["sources"]))
                self.assertEqual(checks[action]["inputs"],
                                 {path: report["build"]["inputs"][path] for path in group["sources"]})
            self.assertTrue(checks["complexity"]["coverage"]["complete"])
            self.assertEqual(checks["complexity"]["limits"]["purpose"], group["purpose"])
            self.assertEqual(set(checks["complexity"]["scanned"]), set(group["sources"]) - set(group["without_explicit_implementations"]))
        self.assertEqual({item["kind"] for item in report["implementation_inventory"]}, {"METHOD_DEF"})
        self.assertEqual(sum(item["file"] == str(service) for item in report["implementation_inventory"]), 3)
        self.assertEqual(self.command(True)["diagnostics"][0]["code"], "SG_DIRTY_BUILD")

    def test_format_and_both_actual_purpose_complexities_fail_without_omitting_other_checks(self):
        service = self.write("src/test-named-production/java/probe/Service.java", '''package probe;

/** 提供超过批准语句数的真实生产反例。 */
class Service {
  String read() {
    int count = 0;
''' + "    count++;\n" * 31 + '''    return String.valueOf(count);
  }
}''')
        test = self.write("verification/java/probe/ServiceTest.java", '''package probe;

class ServiceTest {
  int verify() {
    int count = 0;
''' + "    count++;\n" * 31 + '''    return count;
  }
}''')
        untidy = self.write("src/test-named-production/java/probe/Untidy.java",
                            "package probe;\n/** 提供非标准排版反例。 */\nclass Untidy{int read(){return 1;}}")
        report = self.command(True)
        self.assertTrue(any(item["code"] == "SG_FORMAT" and item["file"] == str(untidy)
                            for item in report["diagnostics"]))
        for path, purpose in ((service, "production"), (test, "test")):
            self.assertTrue(any(item["code"] == "SG_LIMIT" and item["file"] == str(path) and
                                item["rule"] == "ExecutableStatementCount" and item["purpose"] == purpose
                                for item in report["diagnostics"]), report["diagnostics"])
        self.assertEqual(len(report["compilation_checks"]), 2)
        self.assertTrue(all(len(group["checks"]) == 3 for group in report["compilation_checks"]))

    def test_declarations_only_are_not_faked_empty_measurements_or_exempt_from_source_checks(self):
        self.write("src/test-named-production/java/probe/Service.java", '''package probe;

/** 提供没有源码实现的方法声明。 */
interface Service {
  String read();
}''')
        self.write("verification/java/probe/ServiceTest.java", '''package probe;

interface ServiceTest {}''')
        report = self.command()
        self.assertEqual(report["implementation_inventory"], [])
        for group in report["compilation_checks"]:
            self.assertEqual(group["without_explicit_implementations"], group["sources"])
            checks = {item["action"]: item for item in group["checks"]}
            self.assertEqual(checks["complexity"]["proof_scope"], "no-explicit-implementations-in-observed-scope")
            self.assertEqual(checks["complexity"]["scanned"], [])
            self.assertEqual(checks["comments"]["scanned"], group["sources"])
            self.assertEqual(checks["format"]["scanned"], group["sources"])

    def test_caller_cannot_supply_purpose_scan_list_or_report_and_missing_tools_fail_before_build(self):
        for extra in (("--purpose", "test"), ("--sources", "ServiceTest.java"), ("--report", "old.json")):
            with self.subTest(extra=extra):
                process = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project), *extra],
                                         capture_output=True, text=True, timeout=10)
                self.assertEqual(process.returncode, 2, process.stdout + process.stderr)
                self.assertIn("unrecognized arguments", process.stderr)
                self.assertFalse((self.project / "target").exists())
        self.tools = str(self.root / "missing-tools")
        self.assertEqual(self.command(True)["diagnostics"][0]["code"], "SG_TOOL_INPUT")
        self.assertFalse((self.project / "target").exists())
        self.tools = os.environ["DOMAIN_FORGE_GATE_TOOLS"]
        process = subprocess.run([sys.executable, "-B", str(ENTRY), "--environment"],
                                 capture_output=True, text=True, timeout=10)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertEqual(set(json.loads(process.stdout)["environment"]), {"source", "maven"})

    def test_zero_implementations_cannot_bypass_invalid_baseline_and_repair_is_recompiled(self):
        import calibrate_maven_source

        self.write("src/test-named-production/java/probe/Service.java", '''package probe;

/** 提供纯声明的基线拒绝反例。 */
interface Service {}''')
        self.write("verification/java/probe/ServiceTest.java", "package probe;\n\ninterface ServiceTest {}")
        isolated = self.root / "isolated/tools/engineering_gate"
        isolated.mkdir(parents=True)
        for name in calibrate_maven_source.configuration():
            (isolated / name).write_bytes((HERE / name).read_bytes())
        self.entry = isolated / ENTRY.name
        baseline = isolated / "complexity.json"
        original = baseline.read_bytes()
        invalid = json.loads(original)
        invalid["hard_limits"]["production"]["ExecutableStatementCount"] = 0
        baseline.write_text(json.dumps(invalid), encoding="utf-8")
        self.assertEqual(self.command(True)["diagnostics"][0]["code"], "SG_BASELINE_CONFIG")
        self.assertFalse((self.project / "target").exists(), "基线无效时不得先执行构建")
        baseline.write_bytes(original)
        self.assertEqual(self.command()["implementation_inventory"], [])


class MavenSourcePartitionChecks(unittest.TestCase):
    """真实加载器核对固定分组；本项不运行 Maven 或 Java。"""

    def test_fixed_partitions_cover_all_binding_cases_exactly_once(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        module = sys.modules[__name__]
        loader = unittest.TestLoader()
        complete = set(identifiers(loader.loadTestsFromModule(module)))
        selected = set()
        for check, names in (("maven-source-tests", ["MavenSourceStructureSuite", "MavenSourcePartitionChecks"]),
                             ("maven-source-metric-tests", ["MavenSourceMetricSuite"])):
            self.assertIn(check, catalog["checks"], "绑定校准固定分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "tools/engineering_gate/test_maven_source.py", *names],
                             "绑定校准固定分组命令偏离登记合同")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertTrue(all(name in vars(module) for name in names), "绑定校准分组不存在")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group, "绑定校准固定分组不能为空")
            self.assertEqual(len(group), len(set(group)), "绑定用例在固定分组内重复选取")
            group = set(group)
            self.assertFalse(selected & group, "绑定用例被固定分组重复选取")
            selected.update(group)
        self.assertEqual(selected, complete, "固定分组遗漏绑定校准用例")


def MavenSourceStructureSuite():
    """固定类型、结构与调用方输入责任，保留完整行为断言。"""
    names = (
        "test_production_type_rules_follow_actual_compilation_not_name_or_visibility",
        "test_mechanically_valid_sources_include_same_package_tests_without_claiming_method_contracts",
        "test_caller_cannot_supply_purpose_scan_list_or_report_and_missing_tools_fail_before_build",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["MavenSourceCommands." + name for name in names], sys.modules[__name__])


def MavenSourceMetricSuite():
    """固定数值、无显式实现与无效基线责任，保留真实编译和整改。"""
    names = (
        "test_format_and_both_actual_purpose_complexities_fail_without_omitting_other_checks",
        "test_declarations_only_are_not_faked_empty_measurements_or_exempt_from_source_checks",
        "test_zero_implementations_cannot_bypass_invalid_baseline_and_repair_is_recompiled",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["MavenSourceCommands." + name for name in names], sys.modules[__name__])


if __name__ == "__main__":
    unittest.main()
