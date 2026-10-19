"""同次真实构建的源码和架构组合入口，以及关键内部行为说明的目标反例。"""

import json
import os
import pathlib
import subprocess
import sys

from test_maven_scope import MavenScopeFixtures


HERE = pathlib.Path(__file__).resolve().parent


class EngineeringFixtures(MavenScopeFixtures):
    def setUp(self):
        super().setUp()
        self.tools = os.environ["DOMAIN_FORGE_GATE_TOOLS"]
        self.seed()

    def seed(self, comment=True):
        self.write("src/test-named-production/java/probe/Service.java", '''package probe.service;

/** 提供隔离校准所需的固定结果。 */
public class Service {
  /** 返回内部已校验的固定结果。 */
  public String read() {
    return checked();
  }

''' + ("  /** 校验固定输入后返回结果；无外部副作用。 */\n" if comment else "") + '''  private String checked() {
    return "ok";
  }
}''')
        self.write("verification/java/probe/ServiceTest.java", '''package probe.service;

class ServiceTest {
  String verify() {
    return new Service().read();
  }
}''')
        self.definition = {"schema": 3, "modules": [
            {"id": "service", "package": "probe.service", "kind": "component", "depends_on": [],
             "contract_types": ["probe.service.Service"], "contract_members": ["probe.service.Service.read():java.lang.String"],
             "critical_methods": ["probe.service.Service.checked():java.lang.String"]}]}

    def fresh(self, comment=True):
        self.repaired_project()
        self.seed(comment)

    def command(self, diagnostic=None):
        declaration = self.write("modules.json", json.dumps(self.definition))
        process = subprocess.run([sys.executable, "-B", str(HERE / "calibrate_engineering.py"),
                                  "--project", str(self.project), "--declaration", str(declaration)],
                                 capture_output=True, text=True, timeout=60)
        self.assertIn(process.returncode, (0, 1), process.stdout + process.stderr)
        report = json.loads(process.stdout)
        self.assertEqual(process.returncode, int(diagnostic is not None), report)
        if diagnostic:
            self.assertIn(diagnostic, {item.get("code", item.get("rule")) for item in report["diagnostics"]}, report)
        return report


class EngineeringSourceCommands(EngineeringFixtures):
    def test_critical_internal_method_requires_real_comment_and_repairs(self):
        self.seed(False)
        report = self.command("SG_METHOD_JAVADOC")
        self.assertTrue(report["build"]["ok"])
        self.assertEqual(report["method_scope"], {"declared": 2, "bound": 2})
        self.fresh()
        report = self.command()
        self.assertEqual({item["id"] for item in report["method_contracts"]},
                         {"probe.service.Service.read():java.lang.String", "probe.service.Service.checked():java.lang.String"})
        self.assertTrue(report["semantic_obligations"])
        self.assertFalse(report["source_gate_proven"])

    def test_all_compiled_sources_share_same_build_and_format_check(self):
        source = self.project / "verification/java/probe/ServiceTest.java"
        source.write_text('package probe.service; class ServiceTest { String verify(){return new Service().read();}}\n')
        report = self.command("SG_FORMAT")
        self.assertTrue(report["build"]["ok"])
        self.fresh()
        report = self.command()
        self.assertEqual({path for group in report["compilation_checks"] for path in group["sources"]},
                         {path for group in report["build"]["compilations"] for path in group["sources"]})
        for group in report["compilation_checks"]:
            self.assertEqual({check["action"] for check in group["checks"]}, {"comments", "format", "complexity"})
            for check in group["checks"]:
                self.assertEqual(check["inputs"], {path: report["build"]["inputs"][path] for path in check["scanned"]})


class EngineeringArchitectureCommands(EngineeringFixtures):
    def test_test_or_missing_critical_method_cannot_be_reported_as_production_coverage(self):
        for identifier in ("probe.service.ServiceTest.verify():java.lang.String", "probe.service.Service.absent():java.lang.String"):
            self.definition["modules"][0]["critical_methods"] = [identifier]
            self.command("SG_CRITICAL_METHOD")
            self.fresh()
        report = self.command()
        self.assertTrue(report["role_boundaries_checked"])
        self.assertFalse(report["architecture_gate_proven"])

    def test_combined_source_pass_cannot_hide_real_undeclared_boundary_access(self):
        self.write("src/test-named-production/java/other/Reader.java", '''package probe.other;

/** 提供跨模块访问反例。 */
class Reader {
  String read() {
    return new probe.service.Service().read();
  }
}''')
        self.definition["modules"].append({"id": "other", "package": "probe.other", "kind": "component", "depends_on": [],
                                             "contract_types": [], "contract_members": []})
        report = self.command("SG_UNDECLARED_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        self.fresh()
        self.assertTrue(self.command()["ok"])


if __name__ == "__main__":
    import unittest
    unittest.main()
