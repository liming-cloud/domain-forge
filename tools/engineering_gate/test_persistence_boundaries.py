"""用真实 MyBatis-Plus 依赖校准技术隔离；不运行数据库或建立产品工程。"""

import json
import pathlib
import unittest

import test_role_boundaries


HERE = pathlib.Path(__file__).resolve().parent
MP_VERSION = "3.5.17"  # 隔离校准版本，不是产品版本选择。


class PersistenceBoundaryCommands(unittest.TestCase):
    def setUp(self):
        self.role = test_role_boundaries.RoleBoundaryCommands()
        self.role.setUp()
        self.addCleanup(self.role.doCleanups)

    def source(self, role, annotation="", extra=""):
        self.role.source(role, annotation=annotation, extra=extra)
        pom = self.role.case.project / "pom.xml"
        dependencies = "".join('<dependency><groupId>com.baomidou</groupId><artifactId>' + artifact +
                               '</artifactId><version>' + MP_VERSION + '</version></dependency>'
                               for artifact in ("mybatis-plus-core", "mybatis-plus-annotation"))
        pom.write_text(pom.read_text(encoding="utf-8").replace("<dependencies>", "<dependencies>" + dependencies), encoding="utf-8")

    def test_application_cannot_treat_framework_mapper_as_business_port_then_repairs(self):
        self.source("application")
        path = self.role.case.project / "src/test-named-production/java/probe/Service.java"
        path.write_text(path.read_text(encoding="utf-8").replace("public class Service", "public abstract class Service implements com.baomidou.mybatisplus.core.mapper.BaseMapper<String>"), encoding="utf-8")
        self.role.case.write("verification/java/probe/ServiceTest.java", "package probe.service.application; class ServiceTest { String verify(Service service) { return service.read(); } }")
        report = self.role.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"], "必须真实编译成功后因技术隔离失败")
        self.assertTrue(any(item.get("target") == "com.baomidou.mybatisplus.core.mapper.BaseMapper"
                            for item in report["diagnostics"]))
        self.role.case.repaired_project()
        self.source("application")
        self.role.command()

    def test_domain_cannot_hide_persistence_annotation_by_lowering_visibility_then_repairs(self):
        self.source("domain", annotation='@com.baomidou.mybatisplus.annotation.TableName("calibration")')
        path = self.role.case.project / "src/test-named-production/java/probe/Service.java"
        path.write_text(path.read_text(encoding="utf-8").replace("public class Service", "class Service"), encoding="utf-8")
        self.role.case.definition["modules"][0].update(contract_types=[], contract_members=[])
        report = self.role.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        self.role.case.repaired_project()
        self.source("domain")
        self.role.command()

    def test_public_payload_private_page_field_is_a_technical_contract_leak_then_repairs(self):
        self.source("interfaces")
        path = self.role.case.project / "src/test-named-production/java/probe/Service.java"
        path.write_text(path.read_text(encoding="utf-8").replace("public String read()", "public Payload read()").replace('return "ok";', "return null;"), encoding="utf-8")
        self.role.case.write("src/test-named-production/java/probe/Payload.java", '''package probe.service.interfaces;
/** 校准公开数据合同中的私有框架字段。 */
public class Payload {
  private com.baomidou.mybatisplus.core.metadata.IPage<String> records;
}
''')
        self.role.case.write("verification/java/probe/ServiceTest.java", "package probe.service.interfaces; class ServiceTest { Object verify() { return new Service().read(); } }")
        module = self.role.case.definition["modules"][0]
        module.update(contract_types=["probe.service.interfaces.Service", "probe.service.interfaces.Payload"],
                      contract_members=["probe.service.interfaces.Service.read():probe.service.interfaces.Payload"],
                      data_contracts=["probe.service.interfaces.Payload"])
        report = self.role.command("SG_TECHNICAL_CONTRACT")
        self.assertTrue(report["build"]["ok"])
        self.assertTrue(any(item.get("target") == "com.baomidou.mybatisplus.core.metadata.IPage" and item.get("path")
                            for item in report["diagnostics"] if item["code"] == "SG_TECHNICAL_CONTRACT"))
        self.role.case.repaired_project()
        self.source("interfaces")
        self.role.command()

    def test_infrastructure_can_use_actual_mapper_wrapper_page_and_mapping_annotation(self):
        self.source("infrastructure", annotation='@com.baomidou.mybatisplus.annotation.TableName("calibration")', extra='''
  private com.baomidou.mybatisplus.core.mapper.BaseMapper<String> mapper;
  private com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<String> query;
  private com.baomidou.mybatisplus.core.metadata.IPage<String> page;
''')
        report = self.role.command()
        self.assertTrue(report["build"]["ok"])
        self.assertTrue(report["role_boundaries_checked"])
        self.assertFalse(report["architecture_gate_proven"])
        self.assertFalse(report["human_acceptance"])
        baseline = json.loads((HERE / "role-boundaries.lock.json").read_text(encoding="utf-8"))
        self.assertEqual({item["artifact"]: item["version"] for item in baseline["technical_libraries"]},
                         {"mybatis-plus-core": MP_VERSION, "mybatis-plus-annotation": MP_VERSION, "dynamic-datasource-spring": "4.5.0"})


if __name__ == "__main__":
    unittest.main()
