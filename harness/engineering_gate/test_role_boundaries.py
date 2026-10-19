"""真实 Maven 与框架类型的职责边界反例，不建立产品工程或声称事务已生效。"""

import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

import test_maven_architecture


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_roles.py"


class RoleBoundaryCommands(unittest.TestCase):
    def setUp(self):
        self.case = test_maven_architecture.MavenArchitectureCommands()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)
        self.lock = json.loads((HERE / "role-boundaries.lock.json").read_text(encoding="utf-8"))

    def dependencies(self):
        text = "".join('<dependency><groupId>org.springframework</groupId><artifactId>' + artifact +
                       '</artifactId><version>' + self.lock["spring_version"] + '</version></dependency>'
                       for artifact in ("spring-context", "spring-beans", "spring-tx", "spring-jdbc", "spring-core"))
        pom = self.case.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<build>", "<dependencies>" + text + "</dependencies><build>"), encoding="utf-8")

    def source(self, role, annotation="", expression='"ok"', extra=""):
        package = "probe.service." + role
        self.case.write("src/test-named-production/java/probe/Service.java", "package " + package + ";\n/** 隔离工程中的职责校准类型。 */\n" + annotation + '''
public class Service {
  /** 返回职责校准结果。 */
  public String read() { return ''' + expression + "; }\n" + extra + "\n}")
        self.case.write("verification/java/probe/ServiceTest.java", "package " + package +
                        '; class ServiceTest { String verify() { return new Service().read(); } }')
        self.case.definition = {"schema": 3, "modules": [
            {"id": "service", "package": "probe.service", "kind": "context", "depends_on": [],
             "contract_types": [package + ".Service"], "contract_members": [package + ".Service.read():java.lang.String"]}]}
        self.dependencies()

    def command(self, diagnostic=None):
        declaration = self.case.project / "modules.json"
        declaration.write_text(json.dumps(self.case.definition), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.case.project),
                                 "--declaration", str(declaration), "--maven-home", self.case.maven,
                                 "--repository", self.case.repository, "--tools", self.case.tools],
                                capture_output=True, text=True, timeout=30)
        self.assertIn(result.returncode, (0, 1), result.stdout + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, report)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], report)
        return report

    def test_boot4_line_calibrates_actual_spring7_metadata(self):
        self.source("application", "@org.springframework.stereotype.Service\n@org.springframework.transaction.annotation.Transactional")
        report = self.command()
        versions = {item["version"] for compilation in report["build"]["compilations"]
                    for item in compilation["dependencies"] if item["group"] == "org.springframework"}
        self.assertEqual(versions, {self.lock["spring_version"]}, "校准必须来自同次实际解析的框架对象")
        self.assertEqual({version.split(".")[0] for version in versions}, {"7"}, "Boot 4 不能由 Spring 6 夹具代替适配")
        self.assertTrue(report["role_boundaries_checked"])
        self.assertFalse(report["architecture_gate_proven"])
        self.assertFalse(report["human_acceptance"])

    def test_domain_cannot_bind_framework_lifecycle_then_repairs(self):
        self.source("domain", "@org.springframework.stereotype.Service")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"], "必须原生成功后因职责边界拒绝")
        self.case.repaired_project()
        self.source("domain")
        self.assertTrue(self.command()["role_boundaries_checked"])

    def test_application_cannot_operate_client_even_with_allowed_annotations_then_repairs(self):
        self.source("application", "@org.springframework.stereotype.Service", "new org.springframework.jdbc.core.JdbcTemplate().toString()")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"], "不能把编译失败当作技术操作拦截")
        self.case.repaired_project()
        self.source("application", "@org.springframework.stereotype.Service")
        self.assertTrue(self.command()["role_boundaries_checked"])

    def composed_annotation(self, role):
        self.source(role, "@probe.shared.UseCase")
        self.case.write("src/test-named-production/java/shared/UseCase.java", '''package probe.shared;
/** 组合装配注解，仅用于校准间接框架绑定。 */
@org.springframework.stereotype.Service
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface UseCase {}
''')
        self.case.definition["modules"][0]["depends_on"] = ["shared"]
        self.case.definition["modules"].append({"id": "shared", "package": "probe.shared", "kind": "component",
                                               "depends_on": [], "contract_types": ["probe.shared.UseCase"], "contract_members": []})

    def test_composed_annotation_does_not_hide_domain_framework_binding(self):
        self.composed_annotation("domain")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        self.case.repaired_project()
        self.composed_annotation("application")
        self.assertTrue(self.command()["role_boundaries_checked"])

    def test_application_metadata_does_not_require_technical_wrapper_interfaces(self):
        self.source("application", '''@org.springframework.stereotype.Service
@org.springframework.transaction.annotation.Transactional(
 propagation=org.springframework.transaction.annotation.Propagation.REQUIRED,
 isolation=org.springframework.transaction.annotation.Isolation.DEFAULT)''', 'port.read()', '''
  private final Port port;
  /** 接收业务协议，不操作具体客户端。 */
  @org.springframework.beans.factory.annotation.Autowired
  public Service(@org.springframework.beans.factory.annotation.Qualifier("business") Port port) { this.port = port; }
}
/** 校准用技术无关的业务协议。 */
interface Port { String read(); }
/** 校准合法组件元数据。 */
@org.springframework.stereotype.Component
class Companion {
''')
        self.case.write("verification/java/probe/ServiceTest.java", '''package probe.service.application;
class ServiceTest { String verify() { return new Service(() -> "ok").read(); } }
''')
        report = self.command()
        self.assertTrue(report["build"]["ok"])
        self.assertTrue(report["role_boundaries_checked"])
        for key in ("source_gate_proven", "architecture_gate_proven", "contract_closure_proven", "human_acceptance"):
            self.assertFalse(report[key], "装配及事务注解不证明产品事务、完整架构或人工验收")

    def test_annotation_class_literal_cannot_reuse_metadata_permission_then_repairs(self):
        annotation = "@org.springframework.stereotype.Service"
        self.source("application", annotation, 'org.springframework.stereotype.Service.class.getName()')
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        references = [item for item in report["dependency_declarations"]
                      if item["owner"] == "probe.service.application.Service" and item["target"] == "org.springframework.stereotype.Service"]
        self.assertTrue({"annotation", "type"}.issubset({item["usage"] for item in references}))
        self.assertTrue(report["build"]["ok"])
        self.case.repaired_project()
        self.source("application", annotation)
        self.command()

    def test_static_imported_enum_operation_is_not_annotation_metadata_then_repairs(self):
        self.source("application", "@org.springframework.transaction.annotation.Transactional(propagation=REQUIRED)",
                    'Integer.toString(REQUIRED.value())')
        path = self.case.project / "src/test-named-production/java/probe/Service.java"
        text = path.read_text(encoding="utf-8")
        path.write_text(text.replace(";\n", ";\nimport static org.springframework.transaction.annotation.Propagation.REQUIRED;\n", 1), encoding="utf-8")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        self.assertTrue(any(item.get("operation") is True for item in report["diagnostics"]
                            if item["code"] == "SG_TECHNICAL_DEPENDENCY"))
        self.case.repaired_project()
        self.source("application", "@org.springframework.transaction.annotation.Transactional")
        self.command()

    def test_inlined_sql_constant_keeps_consumer_for_named_and_wildcard_imports(self):
        for imported in ("java.sql.Statement.RETURN_GENERATED_KEYS", "java.sql.Statement.*"):
            with self.subTest(imported=imported):
                self.source("domain", expression="Integer.toString(RETURN_GENERATED_KEYS)")
                path = self.case.project / "src/test-named-production/java/probe/Service.java"
                text = path.read_text(encoding="utf-8").replace(";\n", ";\nimport static " + imported + ";\n", 1)
                path.write_text(text, encoding="utf-8")
                report = self.command("SG_TECHNICAL_DEPENDENCY")
                self.assertTrue(report["build"]["ok"])
                self.assertNotIn("java.sql.Statement", {part["target"] for item in report["inventory"] for part in item["dependencies"]},
                                 "原生字节码确实没有该依赖，不能靠编译失败或字节码反例冒充")
                self.assertTrue(any(item["target"] == "java.sql.Statement" and item["usage"] == "type" and
                                    item["owner"] == "probe.service.domain.Service" for item in report["dependency_declarations"]))
            self.case.repaired_project()
        self.source("domain")
        self.command()

    def test_nonpublic_domain_data_source_dependency_is_rejected_then_repairs(self):
        self.source("domain", extra="private javax.sql.DataSource connection;")
        report = self.command("SG_TECHNICAL_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        self.assertTrue(any(item["target"] == "javax.sql.DataSource" for item in report["diagnostics"]
                            if item["code"] == "SG_TECHNICAL_DEPENDENCY"))
        self.case.repaired_project()
        self.source("domain")
        self.command()

    def test_local_and_anonymous_application_code_cannot_use_infrastructure_types(self):
        self.source("application", expression='new Local().read()', extra="")
        path = self.case.project / "src/test-named-production/java/probe/Service.java"
        path.write_text('''package probe.service.application;
/** 校准局部与匿名实现的真实所属职责。 */
public class Service {
  /** 返回校准结果。 */
  public String read() {
    class Local { String read() { return new probe.service.infrastructure.Stored().read(); } }
    java.util.function.Supplier<String> anonymous = new java.util.function.Supplier<>() {
      public String get() { return new probe.service.infrastructure.Stored().read(); }
    };
    return new Local().read() + anonymous.get();
  }
}
''', encoding="utf-8")
        self.case.write("src/test-named-production/java/infra/Stored.java", '''package probe.service.infrastructure;
/** 仅用于检查职责，不代表产品持久化实现。 */
public class Stored { public String read() { return "ok"; } }
''')
        report = self.command("SG_ROLE_DEPENDENCY")
        self.assertTrue(report["build"]["ok"])
        owners = {item["type"] for item in report["diagnostics"] if item["code"] == "SG_ROLE_DEPENDENCY"}
        self.assertTrue({"probe.service.application.Service$1Local", "probe.service.application.Service$1"}.issubset(owners))
        self.case.repaired_project()
        self.source("infrastructure", expression='new org.springframework.jdbc.core.JdbcTemplate().toString()')
        self.command()

    def test_context_roles_use_actual_package_and_tests_keep_actual_purpose(self):
        self.source("unclassified")
        report = self.command("SG_ROLE_OWNERSHIP")
        self.assertTrue(report["build"]["ok"])
        self.case.repaired_project()
        self.source("domain")
        self.case.write("verification/java/probe/ServiceTest.java", '''package probe.service.domain;
class ServiceTest { String verify() { return new org.springframework.jdbc.core.JdbcTemplate().toString(); } }
''')
        report = self.command()
        self.assertEqual(report["role_bindings"]["probe.service.domain.ServiceTest"], "test")

    def test_components_and_assembly_are_not_forced_into_context_positions(self):
        for kind in ("component", "assembly"):
            with self.subTest(kind=kind):
                self.source("runtime", "@org.springframework.stereotype.Service",
                            'new org.springframework.jdbc.core.JdbcTemplate().toString()')
                self.case.definition["modules"][0]["kind"] = kind
                self.assertEqual(self.command()["role_bindings"]["probe.service.runtime.Service"], kind)
                self.case.repaired_project()

    def test_unknown_library_is_a_calibration_gap_not_a_fabricated_coding_violation(self):
        self.source("domain", expression='org.apache.commons.text.StringEscapeUtils.escapeHtml4("ok")')
        pom = self.case.project / "pom.xml"
        text = pom.read_text(encoding="utf-8").replace("</dependencies>", '''<dependency>
<groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId><version>1.14.0</version>
</dependency></dependencies>''')
        pom.write_text(text, encoding="utf-8")
        report = self.command("SG_ROLE_CALIBRATION")
        self.assertTrue(report["build"]["ok"])
        self.assertNotIn("SG_TECHNICAL_DEPENDENCY", {item["code"] for item in report["diagnostics"]})
        self.case.repaired_project()
        self.source("domain", expression='org.apache.commons.lang3.StringUtils.trim(" ok ")')
        pom = self.case.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("</dependencies>", '''<dependency>
<groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.20.0</version>
</dependency></dependencies>'''), encoding="utf-8")
        self.command()

    def test_invalid_role_declaration_is_rejected_before_build_then_repairs(self):
        self.source("domain")
        baseline = json.loads(json.dumps(self.case.definition))
        for variant in ("legacy", "kind", "override", "unknown"):
            with self.subTest(variant=variant):
                self.case.definition = json.loads(json.dumps(baseline))
                if variant == "legacy":
                    self.case.definition["schema"] = 2
                elif variant == "kind":
                    self.case.definition["modules"][0]["kind"] = "test"
                elif variant == "override":
                    self.case.definition["modules"][0]["roles"] = {"domain": "runtime"}
                else:
                    self.case.definition["unexpected"] = True
                self.command("SG_DECLARATION")
                self.assertFalse((self.case.project / "target").exists())
        self.case.definition = baseline
        self.command()

    def test_invalid_role_baseline_fails_before_build_then_restores(self):
        self.source("domain")
        with tempfile.TemporaryDirectory(prefix="domain-forge-role-config-") as temporary:
            copied = pathlib.Path(temporary) / "isolated-checkout/harness/engineering_gate"
            shutil.copytree(HERE, copied, ignore=shutil.ignore_patterns("__pycache__"))
            lock = copied / "role-boundaries.lock.json"
            original = lock.read_text(encoding="utf-8")
            declaration = self.case.project / "modules.json"
            declaration.write_text(json.dumps(self.case.definition), encoding="utf-8")
            argv = [sys.executable, "-B", str(copied / ENTRY.name), "--project", str(self.case.project),
                    "--declaration", str(declaration), "--maven-home", self.case.maven,
                    "--repository", self.case.repository, "--tools", self.case.tools]
            for variant in ("wildcard", "wildcard-client", "version", "unknown", "overlap", "duplicate-key",
                            "technical-wildcard", "technical-version", "technical-overlap"):
                with self.subTest(variant=variant):
                    changed = json.loads(original)
                    if variant == "wildcard":
                        changed["application_annotations"] = ["org.springframework.*"]
                    elif variant == "wildcard-client":
                        changed["technical_types"] = ["org.springframework.*"]
                    elif variant == "version":
                        changed["spring_version"] = "LATEST"
                    elif variant == "unknown":
                        changed["permit_unknown"] = True
                    elif variant == "overlap":
                        changed["annotation_values"] = changed["application_annotations"]
                    elif variant == "technical-wildcard":
                        changed["technical_libraries"][0]["artifact"] = "mybatis-plus-*"
                    elif variant == "technical-version":
                        changed["technical_libraries"][0]["version"] = "LATEST"
                    elif variant == "technical-overlap":
                        changed["utility_libraries"].append(changed["technical_libraries"][0])
                    text = json.dumps(changed)
                    if variant == "duplicate-key":
                        text = '{"schema": 1,' + text[1:]
                    lock.write_text(text, encoding="utf-8")
                    process = subprocess.run(argv, capture_output=True, text=True, timeout=30)
                    self.assertEqual(process.returncode, 1, process.stdout + process.stderr)
                    self.assertEqual(json.loads(process.stdout)["diagnostics"][0]["code"], "SG_ROLE_BASELINE")
                    self.assertFalse((self.case.project / "target").exists())
            lock.write_text(original, encoding="utf-8")
            process = subprocess.run(argv, capture_output=True, text=True, timeout=30)
            self.assertEqual(process.returncode, 0, process.stdout + process.stderr)

    def test_missing_source_usage_observation_cannot_pass_then_restores(self):
        import calibrate_architecture
        import role_boundaries
        from calibrate_source import ProbeFailure
        self.source("application", "@org.springframework.stereotype.Service")
        report = self.command()
        modules = calibrate_architecture.declaration(self.case.project / "modules.json", schema=3)
        original = json.dumps(report)
        for variant in ("owner", "usage", "empty-owner", "unknown-owner"):
            with self.subTest(variant=variant):
                changed = json.loads(original)
                self.assertTrue(changed["dependency_declarations"])
                reference = next(item for item in changed["dependency_declarations"] if item["usage"] != "import")
                if variant == "empty-owner":
                    reference["owner"] = ""
                elif variant == "unknown-owner":
                    reference["owner"] = "probe.unobserved.Missing"
                else:
                    del reference[variant]
                with self.assertRaises(ProbeFailure) as caught:
                    role_boundaries.inspect(changed, modules, role_boundaries.baseline())
                self.assertEqual(caught.exception.diagnostic["code"], "SG_ROLE_SOURCE")
        self.assertFalse(role_boundaries.inspect(json.loads(original), modules, role_boundaries.baseline())[1])

    def test_missing_declaration_has_structured_failure_without_build(self):
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.case.project),
                                 "--declaration", str(self.case.root / "absent.json"), "--maven-home", self.case.maven,
                                 "--repository", self.case.repository, "--tools", self.case.tools],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)["diagnostics"][0]["code"], "SG_DECLARATION")
        self.assertFalse((self.case.project / "target").exists())

    def test_uncalibrated_spring_api_is_not_declared_technical_by_group_name(self):
        self.source("application", expression='org.springframework.util.StringUtils.trimAllWhitespace(" ok ")')
        report = self.command("SG_ROLE_CALIBRATION")
        self.assertTrue(report["build"]["ok"])
        self.assertNotIn("SG_TECHNICAL_DEPENDENCY", {item["code"] for item in report["diagnostics"]})


class RoleBoundaryPartitionChecks(unittest.TestCase):
    def test_fixed_groups_cover_every_case_exactly_once_and_match_ci(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module, loader = sys.modules[__name__], unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "职责模块加载重复用例")
        selected = set()
        for check, names in (("role-boundary-tests", ["RoleBoundaryPolicySuite", "RoleBoundaryPartitionChecks"]),
                             ("role-boundary-usage-tests", ["RoleBoundaryUsageSuite"]),
                             ("role-boundary-ownership-tests", ["RoleBoundaryOwnershipSuite"]),
                             ("role-boundary-input-tests", ["RoleBoundaryInputSuite"])):
            self.assertIn(check, catalog["checks"], "职责固定分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "harness/engineering_gate/test_role_boundaries.py", *names],
                             "职责固定分组命令偏离登记合同")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"],
                             ["@python", "-B", "harness/engineering_gate/calibrate_roles.py", "--environment"],
                             "职责环境预检偏离固定入口")
            command = "        run: python -B harness/engineering_gate/test_role_boundaries.py " + " ".join(names)
            self.assertEqual(workflow.splitlines().count(command), 1, "职责 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group, "职责固定分组不能为空")
            self.assertEqual(len(group), len(set(group)), "职责组内重复用例")
            self.assertFalse(selected & set(group), "职责跨组重复用例")
            selected.update(group)
        self.assertEqual(selected, set(complete), "职责固定分组遗漏用例")


def RoleBoundaryPolicySuite():
    names = ("test_boot4_line_calibrates_actual_spring7_metadata",
             "test_domain_cannot_bind_framework_lifecycle_then_repairs",
             "test_application_cannot_operate_client_even_with_allowed_annotations_then_repairs",
             "test_application_metadata_does_not_require_technical_wrapper_interfaces",
             "test_static_imported_enum_operation_is_not_annotation_metadata_then_repairs")
    return unittest.defaultTestLoader.loadTestsFromNames(["RoleBoundaryCommands." + name for name in names], sys.modules[__name__])


def RoleBoundaryUsageSuite():
    names = ("test_composed_annotation_does_not_hide_domain_framework_binding",
             "test_annotation_class_literal_cannot_reuse_metadata_permission_then_repairs",
             "test_inlined_sql_constant_keeps_consumer_for_named_and_wildcard_imports")
    return unittest.defaultTestLoader.loadTestsFromNames(["RoleBoundaryCommands." + name for name in names], sys.modules[__name__])


def RoleBoundaryOwnershipSuite():
    names = ("test_nonpublic_domain_data_source_dependency_is_rejected_then_repairs",
             "test_local_and_anonymous_application_code_cannot_use_infrastructure_types",
             "test_context_roles_use_actual_package_and_tests_keep_actual_purpose",
             "test_components_and_assembly_are_not_forced_into_context_positions")
    return unittest.defaultTestLoader.loadTestsFromNames(["RoleBoundaryCommands." + name for name in names], sys.modules[__name__])


def RoleBoundaryInputSuite():
    names = ("test_invalid_role_declaration_is_rejected_before_build_then_repairs",
             "test_invalid_role_baseline_fails_before_build_then_restores",
             "test_missing_source_usage_observation_cannot_pass_then_restores",
             "test_missing_declaration_has_structured_failure_without_build",
             "test_uncalibrated_spring_api_is_not_declared_technical_by_group_name",
             "test_unknown_library_is_a_calibration_gap_not_a_fabricated_coding_violation")
    return unittest.defaultTestLoader.loadTestsFromNames(["RoleBoundaryCommands." + name for name in names], sys.modules[__name__])


if __name__ == "__main__":
    unittest.main()
