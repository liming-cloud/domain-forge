"""已确认数值基线的公开入口反例；临时副本不修改项目规则或批准新上限。"""

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


ENTRY = pathlib.Path(__file__).with_name("calibrate_source.py")


class ComplexityBaselineCommands(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="domain-forge-baseline-")
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name).resolve()
        self.entry = ENTRY
        self.tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(self.tools, "需要真实固定工具，缺失不能跳过")

    def command(self, source, purpose="production", extra=()):
        result = subprocess.run([sys.executable, "-B", str(self.entry), "--tools", self.tools,
                                 "complexity", "--purpose", purpose, *extra, str(source)],
                                capture_output=True, text=True, timeout=30)
        return result.returncode, result.stdout, result.stderr

    def isolated(self):
        directory = self.root / "isolated"
        directory.mkdir()
        for name in ("calibrate_source.py", "tools.lock.json", "comments.xml", "methods.xml",
                     "measure.xml", "MetricInventory.java", "complexity.json"):
            (directory / name).write_bytes((ENTRY.parent / name).read_bytes())
        self.entry = directory / "calibrate_source.py"
        return directory / "complexity.json"

    def assert_report(self, result, expected):
        code, output, error = result
        self.assertEqual(code, expected, output + error)
        return json.loads(output)

    def test_fixed_production_and_test_boundaries_and_corrected_positive(self):
        methods = []
        for actual in (9, 10, 11):
            # 一个组合条件产生决定点，不以堆积语句同时触发另一项上限。
            condition = " || ".join("value == " + str(index) for index in range(actual - 1))
            methods.append(f"boolean cc{actual}(int value) {{ return {condition} ? true : false; }}")
        for actual in (29, 30, 31):
            body = "int result = 0; " + "result++; " * (actual - 2) + "return result;"
            methods.append(f"int statements{actual}() {{ {body} }}")
        rows = ",\n".join('"long_calibration_data_' + str(index) + '"' for index in range(65))
        methods.append("String[] data() { return new String[] {\n" + rows + "\n}; }")
        source = self.root / "Boundaries.java"
        for purpose in ("production", "test"):
            with self.subTest(purpose=purpose):
                source.write_text("class Boundaries {\n" + "\n".join(methods) + "\n}\n", encoding="utf-8")
                compile_result = subprocess.run(["javac", "--release", "21", "-proc:none", "-d",
                                                 str(self.root / "classes"), str(source)],
                                                capture_output=True, text=True, timeout=30)
                self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
                report = self.assert_report(self.command(source, purpose), 1)
                violations = [(item["rule"], item["actual"], item["max"]) for item in report["diagnostics"]]
                self.assertCountEqual(violations, [("CyclomaticComplexity", 11, 10),
                                                  ("ExecutableStatementCount", 31, 30)])
                self.assertTrue(report["thresholds_fixed"])
                self.assertEqual(report["proof_scope"], "approved-complexity-subset-only")
                self.assertEqual(report["limits"]["purpose"], purpose)
                self.assertEqual(report["purpose_basis"], "explicit-input-not-inferred")
                self.assertFalse(report["semantic_review_proven"])
                self.assertFalse(report["human_acceptance"])
                self.assertGreater(max(item["actual"] for item in report["advisory"]), 60)
                corrected = "\n".join(item for item in methods if "cc11(" not in item and "statements31(" not in item)
                source.write_text("class Boundaries {\n" + corrected + "\n}\n", encoding="utf-8")
                self.assert_report(self.command(source, purpose), 0)

    def test_baseline_missing_duplicate_unknown_or_invalid_fails_before_tools(self):
        config = self.isolated()
        original = config.read_text(encoding="utf-8")
        source = self.root / "One.java"
        source.write_text("class One { void run() {} }\n", encoding="utf-8")
        mutations = [original.replace('"schema": 1', '"schema": 1, "schema": 1'),
                     original.replace('"schema": 1', '"schema": 1, "skip": true')]
        for purpose in ("production", "test"):
            for value in (0, -1, True, "30"):
                data = json.loads(original)
                data["hard_limits"][purpose]["ExecutableStatementCount"] = value
                mutations.append(json.dumps(data))
        data = json.loads(original)
        del data["hard_limits"]["test"]["CyclomaticComplexity"]
        mutations.append(json.dumps(data))
        for content in mutations:
            config.write_text(content, encoding="utf-8")
            report = self.assert_report(self.command(source), 1)
            self.assertEqual(report["diagnostics"][0]["code"], "SG_BASELINE_CONFIG", report)
        config.unlink()
        report = self.assert_report(self.command(source), 1)
        self.assertEqual(report["diagnostics"][0]["code"], "SG_BASELINE_CONFIG", report)

    def test_baseline_bytes_are_used_and_snapshotted_not_overridden_by_cli(self):
        config = self.isolated()
        data = json.loads(config.read_text(encoding="utf-8"))
        # 只在隔离副本降低上限，证明读取配置；不是变更仓库基线或人工批准。
        data["hard_limits"]["production"]["CyclomaticComplexity"] = 1
        config.write_text(json.dumps(data), encoding="utf-8")
        source = self.root / "Configured.java"
        source.write_text("class Configured { int run(int value) { return value > 0 ? value : 0; } }\n", encoding="utf-8")
        report = self.assert_report(self.command(source), 1)
        self.assertEqual([(item["rule"], item["max"]) for item in report["diagnostics"]], [("CyclomaticComplexity", 1)])
        self.assertEqual(report["configuration"]["complexity.json"], hashlib.sha256(config.read_bytes()).hexdigest())
        self.assert_report(self.command(source, "test"), 0)
        code, output, error = self.command(source, extra=("--complexity-limit", "100", "--statement-limit", "100"))
        self.assertNotEqual(code, 0, output + error)
        self.assertIn("unrecognized arguments", error)

    def test_fixed_limits_do_not_turn_metric_coverage_holes_into_success(self):
        config = self.isolated()
        measure = config.with_name("measure.xml")
        original = measure.read_text(encoding="utf-8")
        measure.write_text(original.replace('    <module name="ExecutableStatementCount">\n      <property name="max" value="-1"/>\n    </module>\n', ""), encoding="utf-8")
        source = self.root / "Callback.java"
        source.write_text("class Callback { java.util.function.IntUnaryOperator callback = value -> value > 0 ? value : 0; void empty() {} }\n", encoding="utf-8")
        report = self.assert_report(self.command(source), 1)
        self.assertIn("SG_METRIC_COVERAGE", [item["code"] for item in report["diagnostics"]], report)
        measure.write_text(original, encoding="utf-8")
        self.assert_report(self.command(source), 0)


if __name__ == "__main__":
    unittest.main()
