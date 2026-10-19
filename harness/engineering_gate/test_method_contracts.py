"""实际生产合同精确选择方法说明；不能按 public、行号或同名方法猜测。"""

import json
import os
import pathlib
import subprocess
import sys
import unittest

from test_maven_scope import MavenScopeFixtures


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_maven_architecture.py"


class MethodContractCommands(MavenScopeFixtures):
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
        self.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
public class Service {
  /** 返回校准结果。 */
  public String read() { return "ok"; }
  String internal() { return "simple"; }
}''')
        self.write("verification/java/probe/ServiceTest.java", '''package probe.service;
class ServiceTest { String verify() { return new Service().read(); } }''')

    def repaired_project(self):
        super().repaired_project()
        self.seed_sources()

    def command(self, diagnostic=None):
        declaration = self.project / "modules.json"
        declaration.write_text(json.dumps(self.definition), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project),
                                 "--declaration", str(declaration), "--maven-home", self.maven,
                                 "--repository", self.repository, "--tools", self.tools],
                                capture_output=True, text=True, timeout=30)
        self.assertIn(result.returncode, (0, 1), result.stdout + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, report)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], report)
        return report

    def test_missing_selected_method_comment_fails_then_fresh_repair_passes(self):
        source = self.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
public class Service { public String read() { return "ok"; } }''')
        report = self.command("SG_METHOD_JAVADOC")
        self.assertTrue(report["build"]["ok"])
        issue = next(item for item in report["diagnostics"] if item["code"] == "SG_METHOD_JAVADOC")
        self.assertEqual(issue["member"], "probe.service.Service.read():java.lang.String")
        self.assertEqual(issue["file"], str(source))
        self.repaired_project()
        report = self.command()
        self.assertTrue(report["declared_method_sources_bound"])
        self.assertTrue(report["declared_method_comments_checked"])
        self.assertFalse(report["method_documentation_proven"])
        self.assertFalse(report["semantic_review_proven"])
        self.assertFalse(report["human_acceptance"])

    def test_overloaded_implicit_public_interface_method_is_selected_exactly(self):
        self.definition["modules"][0]["contract_members"] = ["probe.service.Service.read(int):java.lang.String"]
        source = '''package probe.service;
public interface Service {
  /** 返回无参数结果；不能替另一个重载完成说明。 */
  String read();
  %s
  String read(int code);
}'''
        test = 'package probe.service; class ServiceTest { String verify(Service service) { return service.read(1); } }'
        self.write("src/test-named-production/java/probe/Service.java", source % "")
        self.write("verification/java/probe/ServiceTest.java", test)
        self.command("SG_METHOD_JAVADOC")
        self.repaired_project()
        self.write("src/test-named-production/java/probe/Service.java", source % "/** 根据代码返回结果。 */")
        self.write("verification/java/probe/ServiceTest.java", test)
        report = self.command()
        self.assertEqual([item["id"] for item in report["method_contracts"]], self.definition["modules"][0]["contract_members"])
        self.assertEqual(report["method_contracts"][0]["line"], "6")

    def test_selected_compact_constructor_requires_its_own_comment(self):
        self.definition["modules"][0]["contract_members"].append("probe.service.Service.<init>(java.lang.String):void")
        source = '''package probe.service;
public record Service(String value) {
  %s
  public Service { java.util.Objects.requireNonNull(value); }
  /** 返回创建时的结果。 */
  public String read() { return value; }
}'''
        test = 'package probe.service; class ServiceTest { String verify() { return new Service("ok").read(); } }'
        self.write("src/test-named-production/java/probe/Service.java", source % "")
        self.write("verification/java/probe/ServiceTest.java", test)
        report = self.command("SG_METHOD_JAVADOC")
        self.assertTrue(any(item.get("member") == "probe.service.Service.<init>(java.lang.String):void" for item in report["diagnostics"]))
        self.repaired_project()
        self.write("src/test-named-production/java/probe/Service.java", source % "/** 创建结果，不接受空值。 */")
        self.write("verification/java/probe/ServiceTest.java", test)
        report = self.command()
        self.assertEqual(len(report["method_contracts"]), 2)

    def test_method_comment_after_annotation_fails_then_repaired_position_passes(self):
        source = '''package probe.service;
public class Service implements Protocol {
  %s
  public String read() { return "ok"; }
}'''
        parent = "package probe.service; interface Protocol { /** 返回本次校准结果。 */ String read(); }"
        self.write("src/test-named-production/java/probe/Protocol.java", parent)
        self.write("src/test-named-production/java/probe/Service.java", source % "@Deprecated\n  /** 返回校准结果。 */")
        self.command("SG_METHOD_POSITION")
        self.repaired_project()
        self.write("src/test-named-production/java/probe/Protocol.java", parent)
        self.write("src/test-named-production/java/probe/Service.java", source % "/** 返回校准结果。 */\n  @Deprecated")
        self.command()

    def test_erased_generics_arrays_varargs_and_nested_binary_names_bind(self):
        self.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
import java.util.List;
public class Service {
  public static class Value {}
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
  public @interface Usage {}
  /** 返回校准结果。 */
  public String read() { return "ok"; }
  /** 返回选项数量，不修改输入。 */
  public int[] read(List<String> names, Value... options) { return new int[] {options.length}; }
  /** 返回原始数值数组。 */
  public <T extends Number> T[] copy(T... numbers) { return numbers; }
  /** 返回输入计数。 */
  public @Usage int count(@Usage int value) { return value; }
}''')
        module = self.definition["modules"][0]
        module["contract_types"].extend(["probe.service.Service$Value", "probe.service.Service$Usage"])
        module["contract_members"].extend([
            "probe.service.Service.read(java.util.List,[Lprobe.service.Service$Value;):[I",
            "probe.service.Service.copy([Ljava.lang.Number;):[Ljava.lang.Number;",
            "probe.service.Service.count(int):int"])
        report = self.command()
        self.assertEqual({item["id"] for item in report["method_contracts"]}, set(module["contract_members"]))
        self.assertTrue(all(item["explicit"] == "true" for item in report["method_contracts"]))

    def test_override_needs_real_documented_source_not_annotation_exemption(self):
        source = '''package probe.service;
public class Service implements Protocol {
  @Override public String read() { return "ok"; }
}'''
        self.write("src/test-named-production/java/probe/Protocol.java", "package probe.service; interface Protocol { String read(); }")
        self.write("src/test-named-production/java/probe/Service.java", source)
        self.command("SG_METHOD_INHERITANCE")
        self.repaired_project()
        parent = self.write("src/test-named-production/java/probe/Protocol.java", "package probe.service; interface Protocol { /** 返回本次校准结果。 */ String read(); }")
        self.write("src/test-named-production/java/probe/Service.java", source)
        report = self.command()
        method = report["method_contracts"][0]
        self.assertEqual(method["comment_sources"], [{"member": "probe.service.Protocol.read():java.lang.String", "file": str(parent), "line": 1}])
        self.assertFalse(method["semantic_review_proven"])

    def test_inheritdoc_marker_requires_resolvable_inheritance(self):
        source = '''package probe.service;
public class Service %s {
  /** {@inheritDoc} */
  public String read() { return "ok"; }
}'''
        self.write("src/test-named-production/java/probe/Service.java", source % "")
        self.command("SG_METHOD_INHERITANCE")
        self.repaired_project()
        self.write("src/test-named-production/java/probe/Protocol.java", "package probe.service; interface Protocol { /** 返回本次校准结果。 */ String read(); }")
        self.write("src/test-named-production/java/probe/Service.java", source % "implements Protocol")
        report = self.command()
        self.assertEqual(len(report["method_contracts"][0]["comment_sources"]), 1)

    def test_implicit_constructor_cannot_fake_source_documentation(self):
        self.definition["modules"][0]["contract_members"].append("probe.service.Service.<init>():void")
        report = self.command("SG_METHOD_BINDING")
        self.assertFalse(report["declared_method_sources_bound"])
        self.repaired_project()
        self.write("src/test-named-production/java/probe/Service.java", '''package probe.service;
public class Service {
  /** 创建用于校准的服务。 */
  public Service() {}
  /** 返回校准结果。 */
  public String read() { return "ok"; }
}''')
        self.command()

    def test_lowering_visibility_does_not_remove_declared_method_obligation(self):
        self.write("src/test-named-production/java/probe/Service.java", 'package probe.service; public class Service { String read() { return "ok"; } }')
        report = self.command("SG_CONTRACT_TARGET")
        self.assertFalse(report["declared_contracts_bound"])
        self.assertFalse(report["declared_method_sources_bound"])
        self.assertFalse(report["declared_method_comments_checked"])
        self.repaired_project()
        self.command()

    def test_empty_or_malformed_javadoc_is_not_a_valid_method_description(self):
        source = '''package probe.service;
public class Service {
  %s
  public String read() { return "ok"; }
}'''
        for comment in ("/** @return */", "/** 返回 {@link 。 */"):
            self.write("src/test-named-production/java/probe/Service.java", source % comment)
            self.command("SG_METHOD_JAVADOC")
            self.repaired_project()
        self.command()

    def test_owned_declaration_adapter_keeps_comments_format_and_approved_metrics(self):
        source = HERE / "MethodInventory.java"
        for action in ("comments", "method-presence", "format", "complexity"):
            with self.subTest(action=action):
                argv = [sys.executable, "-B", str(HERE / "calibrate_source.py"), "--tools", self.tools, action]
                if action in ("comments", "complexity"):
                    argv.extend(["--purpose", "production"])
                result = subprocess.run([*argv, str(source)], capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                self.assertEqual(report["scanned"], [str(source)])
                if action == "complexity":
                    self.assertTrue(report["coverage"]["complete"])
                    self.assertTrue(report["coverage"]["implementations"])


class MethodContractPartitionChecks(unittest.TestCase):
    """完整加载结果与固定语义分组逐次相等，不用集合隐藏组内重复。"""

    def test_fixed_groups_cover_all_method_contract_cases_exactly_once(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        module = sys.modules[__name__]
        loader = unittest.TestLoader()
        complete = set(identifiers(loader.loadTestsFromModule(module)))
        selected = set()
        for check, names in (("method-comment-tests", ["MethodCommentSuite", "MethodContractPartitionChecks"]),
                             ("method-source-binding-tests", ["MethodSourceBindingSuite"]),
                             ("method-inheritance-tests", ["MethodInheritanceSuite"])):
            self.assertIn(check, catalog["checks"], "方法合同固定分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "harness/engineering_gate/test_method_contracts.py", *names],
                             "方法合同固定分组命令偏离登记合同")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertTrue(all(name in vars(module) for name in names), "方法合同分组不存在")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group, "方法合同固定分组不能为空")
            self.assertEqual(len(group), len(set(group)), "方法合同用例在固定分组内重复选取")
            group = set(group)
            self.assertFalse(selected & group, "方法合同用例被固定分组重复选取")
            selected.update(group)
        self.assertEqual(selected, complete, "固定分组遗漏方法合同用例")


def MethodCommentSuite():
    """精确说明选择、结构与位置，不扩大到全部内部方法。"""
    names = (
        "test_missing_selected_method_comment_fails_then_fresh_repair_passes",
        "test_overloaded_implicit_public_interface_method_is_selected_exactly",
        "test_method_comment_after_annotation_fails_then_repaired_position_passes",
        "test_empty_or_malformed_javadoc_is_not_a_valid_method_description",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["MethodContractCommands." + name for name in names], sys.modules[__name__])


def MethodSourceBindingSuite():
    """精确二进制签名、显式及隐式构造器。"""
    names = (
        "test_selected_compact_constructor_requires_its_own_comment",
        "test_erased_generics_arrays_varargs_and_nested_binary_names_bind",
        "test_implicit_constructor_cannot_fake_source_documentation",
        "test_owned_declaration_adapter_keeps_comments_format_and_approved_metrics",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["MethodContractCommands." + name for name in names], sys.modules[__name__])


def MethodInheritanceSuite():
    """真实继承来源与降低可见性不能免除既有合同。"""
    names = (
        "test_override_needs_real_documented_source_not_annotation_exemption",
        "test_inheritdoc_marker_requires_resolvable_inheritance",
        "test_lowering_visibility_does_not_remove_declared_method_obligation",
    )
    return unittest.defaultTestLoader.loadTestsFromNames(
        ["MethodContractCommands." + name for name in names], sys.modules[__name__])


if __name__ == "__main__":
    unittest.main()
