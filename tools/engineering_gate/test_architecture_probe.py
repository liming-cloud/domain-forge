"""编译隔离对象后检查真实字节码；不交付业务上下文或产品模块模板。"""

import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


ENTRY = pathlib.Path(__file__).with_name("calibrate_architecture.py")


class ArchitectureProbeCommands(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="domain-forge-architecture-")
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name).resolve()
        self.tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(self.tools, "必须提供实际固定工具，不能跳过")
        self.sources = self.root / "sources"
        self.classes = self.root / "classes"
        self.declaration = self.root / "modules.json"
        self.definition = {
            "schema": 1,
            "modules": [
                {"id": "left", "package": "probe.left", "purpose": "production", "depends_on": ["right"],
                 "contract_types": ["probe.left.Reader"],
                 "contract_members": ["probe.left.Reader.run(probe.right.Service):java.lang.String"]},
                {"id": "right", "package": "probe.right", "purpose": "production", "depends_on": [],
                 "contract_types": ["probe.right.Service"], "contract_members": ["probe.right.Service.read():java.lang.String"]}
            ]
        }
        self.write("probe/left/Reader.java", "package probe.left; public class Reader { public String run(probe.right.Service service) { return service.read(); } }")
        self.write("probe/right/Service.java", 'package probe.right; public class Service { public String read() { return "ok"; } public String internal() { return "hidden"; } }')

    def write(self, relative, text):
        path = self.sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + "\n", encoding="utf-8")
        return path

    def compile(self):
        self.classes.mkdir(exist_ok=True)
        result = subprocess.run(["javac", "--release", "21", "-proc:none", "-d", str(self.classes),
                                 *map(str, sorted(self.sources.rglob("*.java")))],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)

    def command(self, expected=0, roots=None):
        self.declaration.write_text(json.dumps(self.definition), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--tools", self.tools,
                                 "--declaration", str(self.declaration), *map(str, roots or [self.classes])],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def reject(self, diagnostic):
        report = self.command(expected=1)
        self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], report)
        return report

    def test_valid_declared_member_access_has_exact_scanning_and_bounded_proof(self):
        self.compile()
        report = self.command()
        self.assertEqual(report["proof_scope"], "explicit-bytecode-architecture-subset-only")
        self.assertFalse(report["source_ownership_proven"])
        self.assertFalse(report["build_proven"])
        self.assertFalse(report["contract_closure_proven"])
        self.assertFalse(report["human_acceptance"])
        self.assertEqual(sorted(item["name"] for item in report["inventory"]), ["probe.left.Reader", "probe.right.Service"])
        self.assertEqual(len(report["scanned"]), 2)
        self.assertIn("modules.json", {pathlib.Path(path).name for path in report["inputs"]})

    def test_public_internal_method_and_method_reference_are_not_contracts(self):
        for body in ("return service.internal();", "java.util.function.Supplier<String> callback = service::internal; return callback.get();"):
            self.write("probe/left/Reader.java", "package probe.left; public class Reader { public String run(probe.right.Service service) { " + body + " } }")
            self.compile()
            self.reject("SG_INTERNAL_MEMBER")
        self.write("probe/left/Reader.java", "package probe.left; public class Reader { public String run(probe.right.Service service) { return service.read(); } }")
        self.compile()
        self.command()

    def test_field_and_constructor_access_require_exact_contract_members(self):
        self.write("probe/right/Service.java", 'package probe.right; public class Service { public static String value; public String read() { return "ok"; } }')
        for body in ("return probe.right.Service.value;", "return new probe.right.Service().read();"):
            self.write("probe/left/Reader.java", "package probe.left; public class Reader { public String run(probe.right.Service service) { " + body + " } }")
            self.compile()
            self.reject("SG_INTERNAL_MEMBER")
        self.definition["modules"][1]["contract_members"].append("probe.right.Service.<init>():void")
        self.command()

    def test_undeclared_dependency_and_production_using_test_are_rejected(self):
        self.compile()
        self.definition["modules"][0]["depends_on"] = []
        self.reject("SG_UNDECLARED_DEPENDENCY")
        self.definition["modules"][0]["depends_on"] = ["right"]
        self.definition["modules"][1]["purpose"] = "test"
        self.reject("SG_TEST_DEPENDENCY")
        self.definition["modules"][1]["purpose"] = "production"
        self.command()

    def test_generic_internal_type_and_missing_bytecode_are_not_empty_success(self):
        self.write("probe/right/Hidden.java", "package probe.right; public class Hidden {}")
        self.write("probe/left/Reader.java", "package probe.left; public class Reader { private java.util.List<probe.right.Hidden[]> hidden; public String run(probe.right.Service service) { return service.read(); } }")
        self.compile()
        self.reject("SG_INTERNAL_TYPE")
        self.definition["modules"][1]["contract_types"].append("probe.right.Hidden")
        self.command()
        (self.classes / "probe/right/Hidden.class").unlink()
        self.reject("SG_UNRESOLVED_TYPE")
        self.compile()
        self.command()

    def test_nested_local_anonymous_and_lambda_dependencies_are_checked(self):
        self.write("probe/left/Reader.java", """package probe.left;
public class Reader {
  public String run(probe.right.Service service) {
    class Local { String read() { return service.internal(); } }
    Runnable anonymous = new Runnable() { public void run() { service.internal(); } };
    Runnable lambda = () -> service.internal();
    return new Local().read();
  }
}
""")
        self.compile()
        report = self.reject("SG_INTERNAL_MEMBER")
        names = {item["name"] for item in report["inventory"]}
        self.assertIn("probe.left.Reader$1Local", names)
        self.assertIn("probe.left.Reader$1", names)
        self.assertTrue(any("Reader$1Local" in item.get("origin", "") for item in report["diagnostics"]), report)
        self.assertTrue(any("Reader$1" in item.get("origin", "") for item in report["diagnostics"]), report)

    def test_actual_module_cycle_and_unknown_ownership_are_rejected(self):
        self.write("probe/right/Service.java", 'package probe.right; public class Service { private probe.left.Reader reader; public String read() { return "ok"; } }')
        self.definition["modules"][1]["depends_on"] = ["left"]
        self.compile()
        self.reject("SG_MODULE_CYCLE")
        self.write("unowned/Extra.java", "package unowned; class Extra {}")
        self.compile()
        self.reject("SG_UNKNOWN_OWNER")

    def test_missing_contract_return_type_drift_and_overlap_are_rejected(self):
        self.compile()
        self.definition["modules"][1]["contract_members"] = ["probe.right.Service.read():int"]
        self.reject("SG_CONTRACT_TARGET")
        self.definition["modules"][1]["contract_members"] = ["probe.right.Service.read():java.lang.String"]
        self.definition["modules"][1]["package"] = "probe"
        self.reject("SG_DECLARATION")

    def test_inherited_member_requires_resolved_owner_permission_and_dependency(self):
        self.write("probe/base/Base.java", 'package probe.base; public class Base { public String inherited() { return "ok"; } }')
        self.write("probe/right/Service.java", 'package probe.right; public class Service extends probe.base.Base { public String read() { return "ok"; } }')
        self.write("probe/left/Reader.java", "package probe.left; public class Reader { public String run(probe.right.Service service) { return service.inherited(); } }")
        self.definition["modules"].append({"id": "base", "package": "probe.base", "purpose": "production",
                                            "depends_on": [], "contract_types": ["probe.base.Base"],
                                            "contract_members": ["probe.base.Base.inherited():java.lang.String", "probe.base.Base.<init>():void"]})
        self.definition["modules"][1]["depends_on"] = ["base"]
        self.compile()
        self.reject("SG_UNDECLARED_DEPENDENCY")
        self.definition["modules"][0]["depends_on"].append("base")
        self.command()
        self.definition["modules"][2]["contract_members"].remove("probe.base.Base.inherited():java.lang.String")
        self.reject("SG_INTERNAL_MEMBER")

    def test_direct_contract_generic_payload_must_be_declared(self):
        self.write("probe/right/Hidden.java", "package probe.right; public class Hidden {}")
        self.write("probe/right/Service.java", "package probe.right; public class Service { public java.util.List<Hidden[]> read() { return java.util.List.of(); } }")
        self.write("probe/left/Reader.java", "package probe.left; public class Reader { public String run(probe.right.Service service) { return service.read().toString(); } }")
        self.definition["modules"][1]["contract_members"] = ["probe.right.Service.read():java.util.List"]
        self.compile()
        self.reject("SG_CONTRACT_TYPE")
        self.definition["modules"][1]["contract_types"].append("probe.right.Hidden")
        self.command()

    def test_payload_fields_and_generic_inheritance_cannot_hide_internal_types(self):
        self.write("probe/left/Reader.java", "package probe.left; public class Reader { public String run(probe.right.Service service) { return service.read().toString(); } }")
        self.write("probe/right/Hidden.java", "package probe.right; class Hidden {}")
        self.write("probe/right/Base.java", "package probe.right; public class Base<T> {}")
        self.write("probe/right/Service.java", "package probe.right; public class Service { private Hidden implementation; public Payload read() { return null; } }")
        self.definition["modules"][1]["contract_types"].extend(["probe.right.Payload", "probe.right.Base"])
        self.definition["modules"][1]["data_contracts"] = ["probe.right.Payload"]
        self.definition["modules"][1]["contract_members"] = ["probe.right.Service.read():probe.right.Payload"]
        for shape in ("private java.util.List<Hidden[]> values;", "private Payload recursive; private Hidden value;"):
            with self.subTest(shape=shape):
                self.write("probe/right/Payload.java", "package probe.right; public class Payload { " + shape + " }")
                self.compile()
                report = self.reject("SG_CONTRACT_TYPE")
                self.assertTrue(any(item.get("target") == "probe.right.Hidden" and item.get("path") and
                                    item["path"][0] == "probe.right.Service.read():probe.right.Payload"
                                    for item in report["diagnostics"]), report)
        self.write("probe/right/Payload.java", "package probe.right; public class Payload extends Base<Hidden[]> {}")
        self.compile()
        self.reject("SG_CONTRACT_TYPE")
        self.write("probe/right/Base.java", "package probe.right; public class Base<T> { private Hidden inheritedValue; }")
        self.write("probe/right/Payload.java", "package probe.right; public class Payload extends Base<String> {}")
        self.compile()
        self.reject("SG_CONTRACT_TYPE")
        # 不把服务内部状态当传输数据；合法递归及已公开泛型父类不能一律拒绝。
        self.write("probe/right/Base.java", "package probe.right; public class Base<T> { private String inheritedValue; }")
        self.write("probe/right/Payload.java", "package probe.right; public class Payload extends Base<String> { private Payload next; }")
        self.compile()
        self.command()

    def test_declaration_rejects_unknown_missing_duplicate_and_empty_targets(self):
        self.compile()
        original = json.loads(json.dumps(self.definition))
        self.definition["modules"][0]["all_public"] = True
        self.reject("SG_DECLARATION")
        self.definition = json.loads(json.dumps(original))
        self.definition["modules"][0]["depends_on"] = ["missing"]
        self.reject("SG_DECLARATION")
        self.definition = json.loads(json.dumps(original))
        self.definition["modules"][0]["contract_types"] *= 2
        self.reject("SG_DECLARATION")
        self.definition = json.loads(json.dumps(original))
        self.definition["modules"][0]["package"] = "probe.absent"
        self.reject("SG_DECLARATION_TARGET")
        for data in (["probe.right.Service"], ["probe.left.Reader", "probe.left.Reader"], [None], "probe.left.Reader"):
            with self.subTest(data=data):
                self.definition = json.loads(json.dumps(original))
                self.definition["modules"][0]["data_contracts"] = data
                self.reject("SG_DECLARATION")
        self.definition = original
        self.command()

    def test_adapter_itself_passes_real_comment_format_and_fixed_metric_checks(self):
        source = ENTRY.with_name("ArchitectureInventory.java")
        for action in ("comments", "method-presence", "format", "complexity"):
            argv = [sys.executable, "-B", str(ENTRY.with_name("calibrate_source.py")), "--tools", self.tools, action]
            if action in ("comments", "complexity"):
                argv.extend(["--purpose", "production"])
            result = subprocess.run([*argv, str(source)], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_empty_duplicate_symlink_and_corrupt_inputs_are_not_passed(self):
        self.classes.mkdir()
        self.reject("SG_BYTECODE_INPUT")
        self.compile()
        report = self.command(expected=1, roots=[self.classes, self.classes])
        self.assertIn("SG_BYTECODE_INPUT", [item["code"] for item in report["diagnostics"]])
        alias = self.classes / "Alias.class"
        alias.symlink_to(self.classes / "probe/left/Reader.class")
        self.reject("SG_BYTECODE_INPUT")
        alias.unlink()
        directory_alias = self.classes / "hidden-directory"
        directory_alias.symlink_to(self.sources, target_is_directory=True)
        self.reject("SG_BYTECODE_INPUT")
        directory_alias.unlink()
        (self.classes / "Corrupt.class").write_bytes(b"not bytecode")
        self.reject("SG_BYTECODE_INPUT")


class ArchitecturePartitionChecks(unittest.TestCase):
    def test_fixed_groups_cover_every_case_exactly_once_and_match_ci(self):
        here = ENTRY.parent
        catalog = json.loads((here.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (here.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module, loader = sys.modules[__name__], unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "架构模块加载重复用例")
        selected = set()
        for check, names in (("architecture-probe-tests", ["ArchitecturePolicySuite", "ArchitecturePartitionChecks"]),
                             ("architecture-integrity-tests", ["ArchitectureIntegritySuite"])):
            self.assertIn(check, catalog["checks"], "架构固定分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "tools/engineering_gate/test_architecture_probe.py", *names],
                             "架构固定分组命令偏离登记合同")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"],
                             ["@python", "-B", "tools/engineering_gate/calibrate_source.py", "environment"],
                             "架构环境预检偏离固定入口")
            command = "        run: python -B tools/engineering_gate/test_architecture_probe.py " + " ".join(names)
            self.assertEqual(workflow.splitlines().count(command), 1, "架构 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group, "架构固定分组不能为空")
            self.assertEqual(len(group), len(set(group)), "架构组内重复用例")
            self.assertFalse(selected & set(group), "架构跨组重复用例")
            selected.update(group)
        self.assertEqual(selected, set(complete), "架构固定分组遗漏用例")


def ArchitecturePolicySuite():
    names = ("test_valid_declared_member_access_has_exact_scanning_and_bounded_proof",
             "test_public_internal_method_and_method_reference_are_not_contracts",
             "test_field_and_constructor_access_require_exact_contract_members",
             "test_undeclared_dependency_and_production_using_test_are_rejected",
             "test_nested_local_anonymous_and_lambda_dependencies_are_checked")
    return unittest.defaultTestLoader.loadTestsFromNames(["ArchitectureProbeCommands." + name for name in names], sys.modules[__name__])


def ArchitectureIntegritySuite():
    names = ("test_generic_internal_type_and_missing_bytecode_are_not_empty_success",
             "test_actual_module_cycle_and_unknown_ownership_are_rejected",
             "test_missing_contract_return_type_drift_and_overlap_are_rejected",
             "test_inherited_member_requires_resolved_owner_permission_and_dependency",
             "test_direct_contract_generic_payload_must_be_declared",
             "test_payload_fields_and_generic_inheritance_cannot_hide_internal_types",
             "test_declaration_rejects_unknown_missing_duplicate_and_empty_targets",
             "test_adapter_itself_passes_real_comment_format_and_fixed_metric_checks",
             "test_empty_duplicate_symlink_and_corrupt_inputs_are_not_passed")
    return unittest.defaultTestLoader.loadTestsFromNames(["ArchitectureProbeCommands." + name for name in names], sys.modules[__name__])


if __name__ == "__main__":
    unittest.main()
