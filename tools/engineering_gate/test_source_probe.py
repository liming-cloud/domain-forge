"""使用官方真实工具验证声明覆盖与目标诊断；缺少工具不能跳过为通过。"""

import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


ENTRY = pathlib.Path(__file__).with_name("calibrate_source.py")


class SourceProbeFixtures(unittest.TestCase):
    """共享真实工具夹具；不承载测试，也不交付产品模型或功能。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="domain-forge-source-probe-")
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name).resolve()
        self.entry = ENTRY
        self.tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(self.tools, "需要显式提供真实固定工具目录；不得跳过必需场景")

    def source(self, name, text):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("package calibration;\n" + text, encoding="utf-8")
        return target

    def command(self, action, *sources, purpose="production", tools=None, limits=None):
        command = [sys.executable, "-B", str(self.entry), "--tools", self.tools if tools is None else str(tools), action]
        if action in ("comments", "limits", "complexity"):
            command.extend(["--purpose", purpose])
        if limits is not None:
            command.extend(["--complexity-limit", str(limits[0]), "--statement-limit", str(limits[1])])
        command.extend(map(str, sources))
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        try:
            report = json.loads(result.stdout)
        except json.JSONDecodeError:
            self.fail(f"缺少可核对诊断：{result.stdout!r} {result.stderr!r}")
        return result.returncode, report

    def compile(self, *sources):
        result = subprocess.run(["javac", "--release", "21", "-proc:none", "-d", str(self.root / "classes"), *map(str, sources)],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)

    def assert_rule(self, report, source, rule):
        self.assertTrue(any(item.get("file") == str(source) and item.get("rule") == rule
                            for item in report["diagnostics"]), report)


class SourcePartitionChecks(unittest.TestCase):
    """核对真实测试加载范围；本项不运行 Java，也不要求工具环境。"""

    def test_fixed_check_partitions_cover_every_source_test_exactly_once(self):
        catalog = json.loads((ENTRY.parent.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        module = sys.modules[__name__]
        loader = unittest.TestLoader()
        complete = set(identifiers(loader.loadTestsFromModule(module)))
        selected = set()
        for check, names in (("engineering-probe-tests", ["SourceProbeCommands", "SourcePartitionChecks"]),
                             ("engineering-metric-probe-tests", ["SourceMetricBehaviorSuite"]),
                             ("engineering-input-probe-tests", ["SourceInputSuite"])):
            self.assertIn(check, catalog["checks"], "源码校准固定分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"],
                             ["@python", "-B", "tools/engineering_gate/test_source_probe.py", *names],
                             "固定分组命令偏离登记合同")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertTrue(all(name in vars(module) for name in names), "已登记分组不存在")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group, "固定分组不能为空")
            self.assertEqual(len(group), len(set(group)), "同一测试在固定分组内重复选取")
            group = set(group)
            self.assertFalse(selected & group, "同一测试被多个固定分组重复选取")
            selected.update(group)
        self.assertEqual(selected, complete, "固定分组遗漏源码校准测试")


class SourceProbeCommands(SourceProbeFixtures):
    """注释、可见性及排版校准；保留原有完整断言。"""

    def test_documented_non_public_type_is_allowed_and_report_is_bounded(self):
        source = self.source("Valid.java", "/** 表达校准对象的职责。 */\nclass Valid {}\n")
        self.compile(source)
        code, report = self.command("comments", source)
        self.assertEqual(code, 0, report)
        self.assertEqual(report["proof_scope"], "explicit-source-tool-calibration-only")
        self.assertFalse(report["semantic_review_proven"])
        self.assertEqual(report["scanned"], [str(source)])

    def test_removing_public_and_adding_generated_does_not_exempt_type(self):
        sources = [self.source("Visible.java", "public class Visible {}\n"),
                   self.source("Hidden.java", "class Hidden {}\n"),
                   self.source("Marked.java", '@javax.annotation.processing.Generated("calibration")\nclass Marked {}\n')]
        self.compile(*sources)
        code, report = self.command("comments", *sources)
        self.assertNotEqual(code, 0, report)
        for source in sources:
            self.assert_rule(report, source, "MissingJavadocType")

    def test_all_named_forms_include_nested_local_and_anonymous_nested_types(self):
        bodies = {
            "Shapes.java": "interface Shapes {}\nenum Choice { FIRST }\nrecord Value(int number) {}\n@interface Marker {}\n",
            "Nested.java": "/** 承载嵌套声明的校准对象。 */\nclass Nested {\n  private class Hidden {}\n  void local() {\n    class Local {}\n    Runnable callback = new Runnable() {\n      public void run() {}\n      class InAnonymous {}\n    };\n  }\n}\n"}
        sources = [self.source(name, text) for name, text in bodies.items()]
        self.compile(*sources)
        code, report = self.command("comments", *sources)
        self.assertNotEqual(code, 0, report)
        messages = "\n".join(item["message"] for item in report["diagnostics"] if item.get("rule") == "MissingJavadocType")
        for name in ["Shapes", "Choice", "Value", "Marker", "Hidden", "Local", "InAnonymous"]:
            self.assertIn(name, messages, report)

    def test_javadoc_after_or_between_annotations_and_after_modifier_is_rejected(self):
        bodies = ["@Deprecated\n/** 表达校准职责。 */\nclass After {}\n",
                  '@Deprecated\n/** 表达校准职责。 */\n@SuppressWarnings("all")\nclass Between {}\n',
                  "final\n/** 表达校准职责。 */\nclass Modifier {}\n"]
        sources = [self.source(name + ".java", text) for name, text in zip(["After", "Between", "Modifier"], bodies)]
        self.compile(*sources)
        code, report = self.command("comments", *sources)
        self.assertNotEqual(code, 0, report)
        for source in sources:
            self.assert_rule(report, source, "InvalidJavadocPosition")
        for source, text in zip(sources, bodies):
            corrected = text.replace("/** 表达校准职责。 */\n", "")
            source.write_text("package calibration;\n/** 表达校准职责。 */\n" + corrected, encoding="utf-8")
        code, report = self.command("comments", *sources)
        self.assertEqual(code, 0, report)

    def test_multiline_annotations_and_documented_nested_types_are_allowed(self):
        source = self.source("Multiple.java", '/** 校准多注解与嵌套类型的位置。 */\n@Deprecated\n@SuppressWarnings(\n  {"all", "serial"})\nclass Multiple {\n  /** 表达内部校准值。 */\n  private record Value(int number) {}\n}\n')
        self.compile(source)
        code, report = self.command("comments", source)
        self.assertEqual(code, 0, report)

    def test_empty_tag_only_and_ordinary_comments_are_not_type_javadoc(self):
        sources = [self.source("Empty.java", "/** */\nclass Empty {}\n"),
                   self.source("Tags.java", "/** @since 说明 */\nclass Tags {}\n"),
                   self.source("Ordinary.java", "/* 普通说明不是文档注释。 */\nclass Ordinary {}\n")]
        self.compile(*sources)
        code, report = self.command("comments", *sources)
        self.assertNotEqual(code, 0, report)
        for source in sources[:2]:
            self.assert_rule(report, source, "SummaryJavadoc")
        self.assert_rule(report, sources[2], "MissingJavadocType")

    def test_tests_need_no_javadoc_but_adopted_javadoc_must_precede_annotations(self):
        source = self.source("TestCase.java", "class TestCase {\n  void clearScenarioName() {}\n}\n")
        self.compile(source)
        code, report = self.command("comments", source, purpose="test")
        self.assertEqual(code, 0, report)
        source.write_text("package calibration;\n@Deprecated\n/** 说明校准测试。 */\nclass TestCase {}\n", encoding="utf-8")
        code, report = self.command("comments", source, purpose="test")
        self.assertNotEqual(code, 0, report)
        self.assert_rule(report, source, "InvalidJavadocPosition")

    def test_selected_method_probe_does_not_skip_override_private_or_compact_constructor(self):
        source = self.source("Methods.java", "/** 校准明确选中方法的缺失检查。 */\nclass Methods implements Runnable {\n  Methods() {}\n  @Override\n  public void run() {}\n  private int getValue() { return 1; }\n}\n/** 校准构造器。 */\nrecord Constructed(int value) {\n  Constructed {}\n}\n")
        self.compile(source)
        code, report = self.command("method-presence", source)
        self.assertNotEqual(code, 0, report)
        messages = "\n".join(item["message"] for item in report["diagnostics"] if item.get("rule") == "MissingJavadocMethod")
        for name in ["Methods", "run", "getValue", "Constructed"]:
            self.assertIn(name, messages, report)

    def test_formatter_check_never_mutates_and_corrected_output_is_idempotent(self):
        source = self.source("Layout.java", "/** 表达排版校准职责。 */\n@Deprecated\nclass Layout{int value(){return 1;}}\n")
        self.compile(source)
        original = source.read_bytes()
        code, report = self.command("format", source)
        self.assertNotEqual(code, 0, report)
        self.assertIn("SG_FORMAT", [item["code"] for item in report["diagnostics"]])
        self.assertEqual(source.read_bytes(), original)
        formatter = pathlib.Path(self.tools) / "google-java-format-1.37.0-all-deps.jar"
        first = subprocess.run(["java", "-jar", str(formatter), str(source)], capture_output=True, text=True, check=True).stdout
        source.write_text(first, encoding="utf-8")
        code, report = self.command("format", source)
        self.assertEqual(code, 0, report)
        second = subprocess.run(["java", "-jar", str(formatter), str(source)], capture_output=True, text=True, check=True).stdout
        self.assertEqual(first, second)
        code, report = self.command("comments", source)
        self.assertEqual(code, 0, report)


class SourceMetricProbeCommands(SourceProbeFixtures):
    """实际指标、实现范围及工具输入校准；保留原有完整断言。"""

    def test_metrics_measure_real_branches_initializers_anonymous_and_compact_constructor(self):
        source = self.source("Counts.java", "/** 校准真实分支的计量。 */\nclass Counts {\n  Counts(boolean value) { if (value) { throw new IllegalStateException(); } }\n  static { if (System.nanoTime() == 0) { throw new IllegalStateException(); } }\n  { if (System.nanoTime() == 0) { throw new IllegalStateException(); } }\n  int choose(boolean first, boolean second) {\n    if (first && second) { return 1; }\n    return first || second ? 2 : 0;\n  }\n  Runnable callback = new Runnable() {\n    public void run() { if (System.nanoTime() == 0) { throw new IllegalStateException(); } }\n  };\n}\n/** 校准紧凑构造器。 */\nrecord CountValue(int number) {\n  CountValue { if (number < 0) { throw new IllegalArgumentException(); } }\n}\n")
        self.compile(source)
        code, report = self.command("measure", source)
        self.assertEqual(code, 0, report)
        complexities = [item["actual"] for item in report["measurements"] if item["rule"] == "CyclomaticComplexity"]
        self.assertEqual(sorted(complexities), [2, 2, 2, 2, 2, 5], report)
        self.assertFalse(report["thresholds_fixed"])
        self.assertTrue(any(item["rule"] == "MethodLength" for item in report["measurements"]), report)
        self.assertEqual(len([item for item in report["measurements"] if item["rule"] == "ExecutableStatementCount"]), 6, report)

    def test_table_data_and_many_sequential_operations_have_different_length_and_statement_counts(self):
        rows = ",\n".join('"calibration_case_' + str(number) + '_with_explicit_precondition_and_expected_result"' for number in range(65))
        source = self.source("SizeChoices.java", "class SizeChoices {\n  String[] cases() { return new String[] {\n" + rows + "\n}; }\n  int execute(int value) {\n    int result = 0;\n" + "    result += value;\n" * 35 + "    return result;\n  }\n}\n")
        formatter = pathlib.Path(self.tools) / "google-java-format-1.37.0-all-deps.jar"
        formatted = subprocess.run(["java", "-jar", str(formatter), str(source)], capture_output=True, text=True, check=True).stdout
        source.write_text(formatted, encoding="utf-8")
        self.compile(source)
        code, report = self.command("measure", source)
        self.assertEqual(code, 0, report)
        lines = formatted.splitlines()
        table_line = next(number for number, line in enumerate(lines, 1) if "String[] cases()" in line)
        steps_line = next(number for number, line in enumerate(lines, 1) if "int execute(" in line)
        values = {(item["line"], item["rule"]): item["actual"] for item in report["measurements"]}
        self.assertGreater(values[table_line, "MethodLength"], 60, report)
        self.assertEqual(values[table_line, "ExecutableStatementCount"], 1, report)
        self.assertEqual(values[steps_line, "ExecutableStatementCount"], 37, report)
        self.assertEqual(values[table_line, "CyclomaticComplexity"], 1, report)
        self.assertEqual(values[steps_line, "CyclomaticComplexity"], 1, report)

    def test_long_data_is_advisory_and_candidate_limits_do_not_approve_a_baseline(self):
        rows = ",\n".join('"case_' + str(number) + '_with_explicit_precondition_and_expected_result"' for number in range(65))
        source = self.source("Table.java", "class Table {\n  String[] cases() { return new String[] {\n" + rows + "\n}; }\n}\n")
        formatter = pathlib.Path(self.tools) / "google-java-format-1.37.0-all-deps.jar"
        formatted = subprocess.run(["java", "-jar", str(formatter), str(source)], capture_output=True, text=True, check=True).stdout
        source.write_text(formatted, encoding="utf-8")
        self.compile(source)
        for purpose in ("production", "test"):
            with self.subTest(purpose=purpose):
                code, report = self.command("limits", source, purpose=purpose, limits=(1, 1))
                self.assertEqual(code, 0, report)
                self.assertEqual(report["proof_scope"], "explicit-candidate-limits-only")
                self.assertFalse(report["thresholds_fixed"], report)
                self.assertFalse(report["semantic_review_proven"], report)
                self.assertEqual(report["candidate_limits"]["purpose"], purpose)
                self.assertEqual(set(report["candidate_limits"]["max"]), {"CyclomaticComplexity", "ExecutableStatementCount"})
                self.assertGreater(max(item["actual"] for item in report["advisory"]), 60, report)

    def test_each_selected_metric_has_below_equal_above_and_corrected_candidates(self):
        for purpose in ("production", "test"):
            for rule in ("CyclomaticComplexity", "ExecutableStatementCount"):
                with self.subTest(purpose=purpose, rule=rule):
                    methods = []
                    for name, actual in (("below", 2), ("equal", 3), ("above", 4)):
                        if rule == "CyclomaticComplexity":
                            body = "if (value > 0) { value--; }\n" * (actual - 1) + "return value;"
                        else:
                            body = "int result = value;\n" + "result++;\n" * (actual - 2) + "return result;"
                        methods.append("int " + name + "(int value) {\n" + body + "\n}")
                    text = "class Boundaries {\n" + "\n".join(methods) + "\n}\n"
                    source = self.source("Boundaries.java", text)
                    self.compile(source)
                    limits = (3, 100) if rule == "CyclomaticComplexity" else (100, 3)
                    code, report = self.command("limits", source, purpose=purpose, limits=limits)
                    self.assertNotEqual(code, 0, report)
                    violations = [item for item in report["diagnostics"] if item["code"] == "SG_LIMIT"]
                    self.assertEqual([(item["rule"], item["actual"], item["max"]) for item in violations], [(rule, 4, 3)], report)
                    values = [item["actual"] for item in report["measurements"] if item["rule"] == rule]
                    self.assertEqual(sorted(values), [2, 3, 4], report)
                    source.write_text("package calibration;\n" + text.replace(methods[2], methods[1].replace("equal", "above")), encoding="utf-8")
                    self.compile(source)
                    code, report = self.command("limits", source, purpose=purpose, limits=limits)
                    self.assertEqual(code, 0, report)

    def test_candidate_limits_refuse_unknown_coverage_and_non_positive_limits(self):
        source = self.source("CallbackLimit.java", "class CallbackLimit {\n  java.util.function.IntUnaryOperator callback = value -> value > 0 ? value : 0;\n  void empty() {}\n}\n")
        self.compile(source)
        isolated = self.root / "isolated-probe"
        isolated.mkdir()
        for name in ("calibrate_source.py", "tools.lock.json", "comments.xml", "methods.xml", "measure.xml", "MetricInventory.java"):
            (isolated / name).write_bytes((ENTRY.parent / name).read_bytes())
        self.entry = isolated / "calibrate_source.py"
        config = isolated / "measure.xml"
        original = config.read_text(encoding="utf-8")
        config.write_text(original.replace('    <module name="ExecutableStatementCount">\n      <property name="max" value="-1"/>\n    </module>\n', ""), encoding="utf-8")
        code, report = self.command("limits", source, limits=(100, 100))
        self.assertNotEqual(code, 0, report)
        self.assertIn("SG_METRIC_COVERAGE", [item["code"] for item in report["diagnostics"]], report)
        config.write_text(original, encoding="utf-8")
        code, report = self.command("limits", source, limits=(100, 100))
        self.assertEqual(code, 0, report)
        for limits in ((0, 3), (3, 0), (-1, 3)):
            code, report = self.command("limits", source, limits=limits)
            self.assertNotEqual(code, 0, report)
            self.assertIn("SG_LIMIT_INPUT", [item["code"] for item in report["diagnostics"]], report)

    def test_missing_tools_empty_inputs_bad_source_and_modified_jar_do_not_pass(self):
        source = self.source("Valid.java", "/** 表达校准职责。 */\nclass Valid {}\n")
        code, report = self.command("comments", source, tools=self.root / "missing-tools")
        self.assertNotEqual(code, 0, report)
        self.assertIn("SG_TOOL_INPUT", [item["code"] for item in report["diagnostics"]])
        code, report = self.command("comments")
        self.assertNotEqual(code, 0, report)
        self.assertIn("SG_SOURCE_INPUT", [item["code"] for item in report["diagnostics"]])
        source.write_text("class {\n", encoding="utf-8")
        code, report = self.command("comments", source)
        self.assertNotEqual(code, 0, report)
        self.assertIn("SG_TOOL_FAILURE", [item["code"] for item in report["diagnostics"]])
        counterfeit = self.root / "modified-tools"
        counterfeit.mkdir()
        (counterfeit / "checkstyle-14.3.0-all.jar").write_bytes(b"not the pinned artifact")
        code, report = self.command("comments", source, tools=counterfeit)
        self.assertNotEqual(code, 0, report)
        self.assertIn("SG_TOOL_INPUT", [item["code"] for item in report["diagnostics"]])

    def test_duplicate_or_symbolic_link_source_inputs_are_rejected(self):
        source = self.source("Unique.java", "/** 表达校准职责。 */\nclass Unique {}\n")
        for inputs in [(source, source), (self.root / "alias.java",)]:
            if inputs[0].name == "alias.java":
                inputs[0].symlink_to(source)
            code, report = self.command("comments", *inputs)
            self.assertNotEqual(code, 0, report)
            self.assertIn("SG_SOURCE_INPUT", [item["code"] for item in report["diagnostics"]])

    def test_environment_reports_real_runtime_and_locked_artifacts(self):
        code, report = self.command("environment")
        self.assertEqual(code, 0, report)
        self.assertIn("21.0.", report["environment"]["java"])
        self.assertEqual(report["environment"]["tools"]["checkstyle"]["version"], "14.3.0")
        self.assertEqual(report["environment"]["tools"]["formatter"]["version"], "1.37.0")

    def test_measurement_without_implementations_cannot_claim_metric_coverage(self):
        source = self.source("NoOperations.java", "/** 只声明校准类型而没有可计量实现。 */\nclass NoOperations {}\n")
        self.compile(source)
        code, report = self.command("measure", source)
        self.assertNotEqual(code, 0, report)
        self.assertIn("SG_EMPTY_METRICS", [item["code"] for item in report["diagnostics"]])

    def test_lambda_metric_coverage_is_explicit_and_never_inferred_from_other_methods(self):
        source = self.source("Callbacks.java", "class Callbacks {\n  java.util.function.IntUnaryOperator field = value -> {\n    if (value < 0) { return 0; }\n    return value;\n  };\n  java.util.function.IntUnaryOperator expression = value -> value < 0 ? 0 : value;\n  Runnable nested() {\n    return () -> { if (System.nanoTime() == 0) { throw new IllegalStateException(); } };\n  }\n}\n")
        self.compile(source)
        code, report = self.command("measure", source)
        self.assertEqual(code, 0, report)
        self.assertIn("coverage", report, "已有数字不能代替每个真实实现的指标覆盖")
        self.assertIn("tool_coverage", report, "保留原工具缺口，不用适配后结果改写来源事实")
        self.assertFalse(report["tool_coverage"]["complete"])
        missing = report["tool_coverage"]["missing"]
        self.assertEqual(len([item for item in missing if item["kind"] == "LAMBDA" and
                              item["rule"] == "CyclomaticComplexity"]), 3, report)
        self.assertTrue(all(item["file"] == str(source) for item in missing), report)
        self.assertTrue(report["coverage"]["complete"], report)
        complexity = [item for item in report["measurements"] if item["rule"] == "CyclomaticComplexity"]
        self.assertEqual(sorted(item["actual"] for item in complexity if item["kind"] == "LAMBDA"), [2, 2, 2], report)
        self.assertEqual([item["actual"] for item in complexity if item["kind"] == "METHOD_DEF"], [1], report)

    def test_nested_lambda_and_local_class_have_independent_complexity_not_double_counting(self):
        source = self.source("NestedCounts.java", "class NestedCounts {\n  java.util.function.Supplier<java.util.function.IntUnaryOperator> callbacks =\n      () -> value -> value > 0 ? value : 0;\n  void outer() {\n    class Local { int choose(int value) { return value > 0 ? value : 0; } }\n    Runnable callback = () -> { if (System.nanoTime() == 0) { throw new IllegalStateException(); } };\n  }\n}\n")
        self.compile(source)
        code, report = self.command("measure", source)
        self.assertEqual(code, 0, report)
        self.assertIn("tool_coverage", report)
        complexity = [item for item in report["measurements"] if item["rule"] == "CyclomaticComplexity"]
        self.assertEqual(sorted(item["actual"] for item in complexity), [1, 1, 2, 2, 2], report)
        self.assertTrue(report["coverage"]["complete"], report)

    def test_decision_points_cover_loops_switch_catch_and_pattern_guard(self):
        source = self.source("DecisionPoints.java", "class DecisionPoints {\n  int loops(int value) {\n    for (int index = 0; index < 1; index++) { value++; }\n    for (int entry : new int[] {1}) { value += entry; }\n    while (value < 0) { value++; }\n    do { value--; } while (value > 1);\n    return value;\n  }\n  int choose(int value) {\n    try {\n      switch (value) {\n        case 0: return 1;\n        case 1, 2: return 2;\n        default: return 3;\n      }\n    } catch (IllegalArgumentException error) { return 0; }\n  }\n  int pattern(Object value) {\n    return switch (value) {\n      case String text when text.isEmpty() || text.length() > 2 -> 1;\n      default -> 0;\n    };\n  }\n}\n")
        self.compile(source)
        code, report = self.command("measure", source)
        self.assertEqual(code, 0, report)
        expected = [5, 4, 4]
        self.assertEqual([item["actual"] for item in report["measurements"] if item["rule"] == "CyclomaticComplexity"], expected, report)
        self.assertEqual([item["actual"] for item in report["tool_measurements"] if item["rule"] == "CyclomaticComplexity"], expected, report)
        self.assertTrue(report["coverage"]["complete"], report)
        self.assertFalse(any(item["kind"] == "LAMBDA" for item in report["coverage"]["implementations"]), report)

    def test_owned_metric_adapter_has_actual_compilation_comments_format_and_coverage(self):
        source = ENTRY.with_name("MetricInventory.java")
        for action in ("comments", "method-presence", "format", "complexity"):
            with self.subTest(action=action):
                code, report = self.command(action, source)
                self.assertEqual(code, 0, report)
                if action == "complexity":
                    self.assertTrue(report["coverage"]["complete"], report)
                    self.assertFalse(any(item["kind"] == "LAMBDA" for item in report["coverage"]["implementations"]), report)

    def test_production_and_test_calibration_samples_execute_real_success_and_refusal_assertions(self):
        # 仅用于门禁数值校准，不把普通标签处理当作产品身份、安全或业务合同。
        production = self.source("SampleLabels.java", """/** 承载隔离校准的普通标签约束，不作为产品接口。 */
class SampleLabels {
  /** 校准多种前提、遍历与合法字符的组合，不承载认证或编码解码。 */
  static String validate(String raw, int maximum) {
    if (raw == null) { throw new IllegalArgumentException("缺少标签"); }
    String label = raw.trim();
    if (maximum < 1 || label.isEmpty() || label.length() > maximum) {
      throw new IllegalArgumentException("长度不符合校准前提");
    }
    for (int index = 0; index < label.length(); index++) {
      char value = label.charAt(index);
      if (!Character.isLetterOrDigit(value) && value != '_' && value != '-') {
        throw new IllegalArgumentException("包含校准不允许的字符");
      }
    }
    return label;
  }
}
""")
        tests = self.source("LabelScenarios.java", """class LabelScenarios {
  public static void main(String[] args) {
    acceptsNormalAndBoundaryLabels();
    refusesInvalidPremisesAndCharacters();
  }
  static void acceptsNormalAndBoundaryLabels() {
    for (String[] example : acceptedExamples()) {
      equal(example[1], SampleLabels.validate(example[0], 4));
    }
    equal("中", SampleLabels.validate("中", 1));
  }
  static String[][] acceptedExamples() {
    return new String[][] {{" A1_ ", "A1_"}, {"A-1_", "A-1_"}, {"中", "中"}};
  }
  static void refusesInvalidPremisesAndCharacters() {
    rejected(() -> SampleLabels.validate(null, 3));
    rejected(() -> SampleLabels.validate("A", 0));
    rejected(() -> SampleLabels.validate("   ", 3));
    rejected(() -> SampleLabels.validate("AAAA", 3));
    rejected(() -> SampleLabels.validate("A B", 3));
    rejected(() -> SampleLabels.validate("A!", 3));
  }
  static void equal(String expected, String actual) {
    if (!expected.equals(actual)) { throw new AssertionError("合法边界结果不符"); }
  }
  static void rejected(Runnable action) {
    try { action.run(); }
    catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("无效对象没有被拒绝");
  }
}
""")
        formatter = pathlib.Path(self.tools) / "google-java-format-1.37.0-all-deps.jar"
        formatted = subprocess.run(["java", "-jar", str(formatter), "--replace", str(production), str(tests)],
                                   capture_output=True, text=True, timeout=30)
        self.assertEqual(formatted.returncode, 0, formatted.stderr)
        self.compile(production, tests)
        executed = subprocess.run(["java", "-cp", str(self.root / "classes"), "calibration.LabelScenarios"],
                                  capture_output=True, text=True, timeout=30)
        self.assertEqual(executed.returncode, 0, executed.stderr)
        self.calibration = {}
        for purpose, source in (("production", production), ("test", tests)):
            code, report = self.command("limits", source, purpose=purpose, limits=(10, 30))
            self.assertEqual(code, 0, report)
            self.assertTrue(report["coverage"]["complete"], report)
            self.assertFalse(report["thresholds_fixed"], report)
            if purpose == "production":
                self.assertEqual([item["actual"] for item in report["measurements"] if item["rule"] == "CyclomaticComplexity"], [9], report)
            self.calibration[purpose] = [{"rule": item["rule"], "actual": item["actual"], "line": item["line"]}
                                         for item in report["measurements"]]

    def test_abstract_declarations_are_not_implementations_but_annotated_default_methods_are(self):
        source = self.source("Partial.java", "interface Partial {\n  int abstractCall();\n  @Deprecated\n  default int implemented() { return 1; }\n}\n")
        self.compile(source)
        code, report = self.command("limits", source, limits=(1, 1))
        self.assertEqual(code, 0, report)
        self.assertEqual(len(report["coverage"]["implementations"]), 1, report)
        self.assertTrue(report["coverage"]["complete"], report)
        abstract_only = self.source("AbstractOnly.java", "interface AbstractOnly { int abstractCall(); }\n")
        self.compile(abstract_only)
        code, report = self.command("measure", abstract_only)
        self.assertNotEqual(code, 0, report)
        self.assertIn("SG_EMPTY_METRICS", [item["code"] for item in report["diagnostics"]], report)

    def test_empty_and_same_line_implementations_have_distinct_zero_statement_measurements(self):
        source = self.source("Small.java", "class Small { void first() {} void second() {} }\n")
        self.compile(source)
        code, report = self.command("measure", source)
        self.assertEqual(code, 0, report)
        self.assertIn("coverage", report)
        self.assertTrue(report["coverage"]["complete"], report)
        statements = [item for item in report["measurements"] if item["rule"] == "ExecutableStatementCount"]
        self.assertEqual(len(statements), 2, report)
        self.assertEqual([item["actual"] for item in statements], [0, 0], report)
        self.assertEqual(len({(item["line"], item["column"]) for item in statements}), 2, report)

    def test_prepare_reuses_verified_artifacts_and_does_not_overwrite_corruption(self):
        code, report = self.command("prepare")
        self.assertEqual(code, 0, report)
        self.assertEqual(report["downloaded"], [])
        counterfeit = self.root / "corrupted-tools"
        counterfeit.mkdir()
        target = counterfeit / "checkstyle-14.3.0-all.jar"
        target.write_bytes(b"preserve this invalid input for diagnosis")
        code, report = self.command("prepare", tools=counterfeit)
        self.assertNotEqual(code, 0, report)
        self.assertIn("SG_TOOL_INPUT", [item["code"] for item in report["diagnostics"]])
        self.assertEqual(target.read_bytes(), b"preserve this invalid input for diagnosis")


def SourceMetricBehaviorSuite():
    """固定指标行为及边界分组；新增用例须由覆盖核对明确分配。"""
    names = (
        "test_metrics_measure_real_branches_initializers_anonymous_and_compact_constructor",
        "test_table_data_and_many_sequential_operations_have_different_length_and_statement_counts",
        "test_long_data_is_advisory_and_candidate_limits_do_not_approve_a_baseline",
        "test_each_selected_metric_has_below_equal_above_and_corrected_candidates",
        "test_nested_lambda_and_local_class_have_independent_complexity_not_double_counting",
        "test_decision_points_cover_loops_switch_catch_and_pattern_guard",
        "test_abstract_declarations_are_not_implementations_but_annotated_default_methods_are",
        "test_empty_and_same_line_implementations_have_distinct_zero_statement_measurements",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["SourceMetricProbeCommands." + name for name in names], sys.modules[__name__])


def SourceInputSuite():
    """固定工具、输入及计量覆盖分组，不减少真实执行或断言。"""
    names = (
        "test_candidate_limits_refuse_unknown_coverage_and_non_positive_limits",
        "test_missing_tools_empty_inputs_bad_source_and_modified_jar_do_not_pass",
        "test_duplicate_or_symbolic_link_source_inputs_are_rejected",
        "test_environment_reports_real_runtime_and_locked_artifacts",
        "test_measurement_without_implementations_cannot_claim_metric_coverage",
        "test_lambda_metric_coverage_is_explicit_and_never_inferred_from_other_methods",
        "test_owned_metric_adapter_has_actual_compilation_comments_format_and_coverage",
        "test_production_and_test_calibration_samples_execute_real_success_and_refusal_assertions",
        "test_prepare_reuses_verified_artifacts_and_does_not_overwrite_corruption",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["SourceMetricProbeCommands." + name for name in names], sys.modules[__name__])


if __name__ == "__main__":
    unittest.main()
