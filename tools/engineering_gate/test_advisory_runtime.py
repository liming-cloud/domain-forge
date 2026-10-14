"""用原生 Maven 扫描校准基础告警，不伪造漏洞报告或证明产品安全。"""

import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_advisory.py"
CONFIG = HERE / "maven-dependency-check.xml"


class AdvisoryRuntimeCommands(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="domain-forge-advisory-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.maven = os.environ.get("DOMAIN_FORGE_GATE_MAVEN", "")
        self.repository = os.environ.get("DOMAIN_FORGE_GATE_ADVISORY_REPOSITORY", "")
        self.data = os.environ.get("DOMAIN_FORGE_GATE_ADVISORY_DATA", "")
        self.assertTrue(self.maven and self.repository and self.data, "必须准备真实 Maven、原生资料和专用依赖缓存，不跳过")
        self.assertTrue((Path(self.data) / "odc.mv.db").is_file(), "缺少原生资料，不能把未执行记为通过")
        self.project = self.root / "normal"
        self.project.mkdir()
        self.pom()

    def pom(self, extra=""):
        (self.project / "pom.xml").write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>probe</groupId><artifactId>advisory</artifactId><version>1.0.0</version>
<dependencies><dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.20.0</version></dependency>
''' + extra + "</dependencies><build><plugins>" + CONFIG.read_text(encoding="utf-8") +
            "</plugins></build></project>", encoding="utf-8")

    def command(self, diagnostic=None, **overrides):
        values = dict(project=str(self.project), maven_home=self.maven, repository=self.repository, data=self.data)
        values.update(overrides)
        argv = [sys.executable, "-B", str(ENTRY)]
        for name, value in values.items():
            argv.extend(["--" + name.replace("_", "-"), value])
        result = subprocess.run(argv, capture_output=True, text=True, timeout=40)
        self.assertTrue(result.stdout.strip(), "基础扫描入口尚未提供实际行为：" + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, report)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], report)
        return report

    def test_normal_native_scan_produces_reports_without_claiming_product_safety(self):
        before = hashlib.sha256((Path(self.data) / "odc.mv.db").read_bytes()).hexdigest()
        report = self.command()
        self.assertEqual(report["proof_scope"], "isolated-native-maven-advisory-only")
        self.assertEqual(report["exit_code"], 0)
        self.assertEqual(report["engine_version"], "13.0.0")
        self.assertTrue(any(item["file"] == "commons-lang3-3.20.0.jar" for item in report["dependencies"]))
        for item in report["reports"].values():
            path = Path(item["path"])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), item["sha256"])
        self.assertEqual(before, hashlib.sha256((Path(self.data) / "odc.mv.db").read_bytes()).hexdigest())
        self.assertIn("BUILD SUCCESS", Path(report["log"]).read_text(encoding="utf-8"))
        for field in ("product_scan_proven", "online_update_proven", "human_acceptance"):
            self.assertFalse(report[field])

    def test_test_scope_vulnerability_and_transitive_dependency_warn_without_failing_build(self):
        self.pom('''<dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-core</artifactId>
<version>2.14.1</version><scope>test</scope></dependency>''')
        report = self.command()
        self.assertEqual(report["exit_code"], 0)
        self.assertTrue(any(item["file"] == "log4j-api-2.14.1.jar" for item in report["dependencies"]))
        core = next(item for item in report["dependencies"] if item["file"] == "log4j-core-2.14.1.jar")
        self.assertIn({"id": "CVE-2021-44228", "severity": "CRITICAL"}, core["findings"])
        log = Path(report["log"]).read_text(encoding="utf-8")
        self.assertIn("[WARNING]", log)
        self.assertIn("CVE-2021-44228", log)

    def test_missing_native_data_is_analysis_failure_then_repaired_in_fresh_project(self):
        empty = self.root / "empty-data"
        empty.mkdir()
        report = self.command("SG_ADVISORY_SCAN", data=str(empty))
        self.assertNotEqual(report["diagnostics"][0]["exit_code"], 0)
        self.assertIn("NoDataException", Path(report["diagnostics"][0]["log"]).read_text(encoding="utf-8"))
        self.assertFalse((self.project / "target/dependency-check-report.json").exists())
        self.assertEqual(list(empty.iterdir()), [], "失败扫描也不改写调用方资料目录")
        self.project = self.root / "repaired"
        self.project.mkdir()
        self.pom()
        self.command()

    def test_stale_output_configuration_drift_and_unsafe_preparation_are_rejected(self):
        target = self.project / "target"
        target.mkdir()
        marker = target / "keep.txt"
        marker.write_text("preserve evidence", encoding="utf-8")
        self.command("SG_DIRTY_BUILD")
        self.assertEqual(marker.read_text(encoding="utf-8"), "preserve evidence")
        self.project = self.root / "changed-config"
        self.project.mkdir()
        self.pom()
        pom = self.project / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("<skipTestScope>false", "<skipTestScope>true"), encoding="utf-8")
        self.command("SG_ADVISORY_CONFIG")
        self.assertFalse((self.project / "target").exists())
        unsafe = subprocess.run([sys.executable, "-B", str(ENTRY), "--prepare", str(HERE.parents[1]),
                                 "--maven-home", self.maven, "--repository", self.repository], capture_output=True, text=True, timeout=10)
        self.assertEqual(unsafe.returncode, 1, unsafe.stdout + unsafe.stderr)
        self.assertEqual(json.loads(unsafe.stdout)["diagnostics"][0]["code"], "SG_ADVISORY_INPUT")
        override = subprocess.run([sys.executable, "-B", str(ENTRY), "--environment", "--skip"],
                                   capture_output=True, text=True, timeout=10)
        self.assertEqual(override.returncode, 2)
        self.assertIn("unrecognized arguments", override.stderr)


class AdvisoryWorkflowChecks(unittest.TestCase):
    def test_fixed_parallel_groups_keep_all_registered_calibration_commands_once(self):
        self.assert_workflow((HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8"))

    def assert_bounded_budget(self, job):
        budgets = re.findall(r"^    timeout-minutes: ([0-9]+)$", job, re.M)
        self.assertTrue(len(budgets) == 1 and int(budgets[0]) > 0, "执行须有唯一正整数预算")

    def assert_workflow(self, workflow):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        regular = workflow.split("  mechanism-checks:\n", 1)[1]
        self.assertEqual(re.findall(r"^        group: \[(.*?)\]$", regular, re.M),
                         ["source, build, architecture, dependencies, roles, methods, http, credentials, policies, passwords, migration, persistence-routing, persistence-state, runtime"],
                         "固定并行组缺失或偏离")
        self.assertEqual(re.findall(r"^      fail-fast: (true|false)$", regular, re.M), ["false"],
                         "一个分组失败不能取消其他必要校准")
        self.assertFalse(re.search(r"^    if:", regular, re.M), "常规校准不能另加跳过条件")
        self.assert_bounded_budget(regular)
        by_file = {
            "test_source_probe.py": "source", "test_complexity_baseline.py": "source", "test_maven_source.py": "source",
            "test_maven_scope.py": "build", "test_maven_wrapper.py": "build", "test_maven_lifecycle.py": "build",
            "test_architecture_probe.py": "architecture", "test_maven_architecture.py": "architecture",
            "test_dependency_declarations.py": "dependencies", "test_maven_advisory.py": "dependencies",
            "test_advisory_runtime.py": "dependencies",
            "test_role_boundaries.py": "roles", "test_method_contracts.py": "methods", "test_persistence_boundaries.py": "roles",
            "test_http_contract.py": "http", "test_http_consumer.py": "http", "test_engineering.py": "roles",
            "test_runtime.py": "runtime", "test_observation.py": "runtime", "test_flyway.py": "migration",
            "test_persistence.py": "persistence-state",
            "test_persistence_adapters.py": "persistence-state",
            "test_access_state.py": "policies", "test_sa_access.py": "credentials", "test_password.py": "passwords", "test_sa_policy.py": "policies",
        }
        routing = {"PersistenceBehaviorCommands", "PersistenceRoutingCommands", "PersistenceTransactionCommands",
                   "PersistenceExecutionCommands", "PersistenceContextCommands", "PersistenceBindingCommands",
                   "AdapterMappingCommands", "AdapterPaginationCommands", "AdapterPaginationPluginCommands", "AdapterIntegrityCommands", "AdapterMigrationProvenanceCommands"}
        expected = {tuple(["python", *catalog["checks"][name]["argv"][1:]])
                    for name in catalog["maintenance"]["checks"]}
        observed = []
        steps = re.findall(r"^      - (?:name|uses):.*?(?=^      - |\Z)", regular, re.M | re.S)
        for step in steps:
            commands = re.findall(r"^        run: (python -B .*test_.*\.py.*?)$", step, re.M)
            for command in commands:
                argv = tuple(shlex.split(command))
                filename = next((Path(arg).name for arg in argv if Path(arg).name.startswith("test_") and arg.endswith(".py")), None)
                self.assertIn(filename, by_file, "新校准须归入明确职责组")
                group = "persistence-routing" if filename in {"test_persistence.py", "test_persistence_adapters.py"} and routing.intersection(argv) else by_file[filename]
                if filename == "test_access_state.py":
                    group = {'AccessLoginSecretCommands': 'policies', 'AccessHeaderSecretCommands': 'policies', 'AccessNewHashSecretCommands': 'passwords', 'AccessFailedLoginSecretCommands': 'credentials', 'AccessResourceCommands': 'policies', 'AccessScopeCommands': 'policies', 'AccessOwnerCommands': 'policies', 'AccessOperationCommands': 'policies', 'AccessSessionCommands': 'credentials', 'AccessExpirationCommands': 'credentials', 'AccessChangesCommands': 'policies', 'AccessPermissionCommands': 'policies', 'AccessRoleCommands': 'policies', 'AccessPasswordCommands': 'passwords', 'AccessFailuresCommands': 'passwords', 'AccessThrottleCommands': 'credentials', 'AccessAuditCommands': 'passwords', 'AccessAuditFailureCommands': 'passwords', 'AccessReconciliationCommands': 'passwords', 'AccessSecretCommands': 'credentials', 'AccessRedisCommands': 'credentials', 'AccessProjectCommands': 'credentials', 'AccessInputChecks': 'policies', 'AccessToolQualityChecks': 'policies', 'AccessStatePartitionChecks': 'policies'}.get(argv[-1])
                self.assertEqual(re.findall(r"^        if: (.*?)$", step, re.M),
                                 ["matrix.group == '" + group + "'"], "校准入口错组或被跳过")
                observed.append(argv)
        self.assertEqual(len(observed), len(set(observed)), "并行校准入口重复")
        self.assertEqual(set(observed), expected, "并行校准入口遗漏或未登记")
        self.assertEqual(len(expected), len(catalog["maintenance"]["checks"]), "校准登记重复命令")
        for command in ("calibrate_source.py prepare", "calibrate_maven.py --prepare",
                        "calibrate_wrapper.py --prepare", "calibrate_advisory.py --environment", "calibrate_http.py --prepare", "calibrate_access.py --prepare", "calibrate_password.py --prepare"):
            matching = [step for step in steps if command in step]
            self.assertEqual(len(matching), 1, "每组必须保留唯一资料准备：" + command)
            self.assertNotRegex(matching[0], r"(?m)^        if:", "资料准备不能因分组跳过")
        migration = [step for step in steps if "calibrate_flyway.py --prepare" in step]
        self.assertEqual((len(migration), regular.count("calibrate_flyway.py --prepare")), (1, 1), "迁移依赖与镜像须唯一准备")
        self.assertEqual(re.findall(r"^        if: (.*?)$", migration[0], re.M),
                         ["matrix.group == 'migration' || matrix.group == 'persistence-routing' || matrix.group == 'persistence-state' || matrix.group == 'credentials' || matrix.group == 'policies' || matrix.group == 'passwords'"],
                         "迁移准备须覆盖迁移及两个持久化组，不得跳过")
        persistence = [step for step in steps if "calibrate_persistence.py --prepare" in step]
        self.assertEqual((len(persistence), regular.count("calibrate_persistence.py --prepare")), (1, 1), "持久化依赖须唯一准备")
        self.assertEqual(re.findall(r"^        if: (.*?)$", persistence[0], re.M),
                         ["matrix.group == 'persistence-routing' || matrix.group == 'persistence-state'"],
                         "持久化准备须覆盖两个固定组，不得跳过")

        access_state = [step for step in steps if "calibrate_access_state.py --prepare" in step]
        self.assertEqual((len(access_state), regular.count("calibrate_access_state.py --prepare")), (1, 1), "访问状态依赖与镜像须唯一准备")
        self.assertEqual(re.findall(r"^        if: (.*?)$", access_state[0], re.M),
                         ["matrix.group == 'credentials' || matrix.group == 'policies' || matrix.group == 'passwords'"],
                         "访问状态准备须覆盖三个访问组")

        runtime = [step for step in steps if "calibrate_runtime.py --prepare" in step]
        self.assertEqual((len(runtime), regular.count("calibrate_runtime.py --prepare")), (1, 1), "运行依赖须唯一准备")
        self.assertEqual(re.findall(r"^        if: (.*?)$", runtime[0], re.M),
                         ["matrix.group == 'runtime'"], "运行组必须准备自身原生依赖")

    def test_missing_duplicate_misrouted_or_skipped_checks_cannot_certify_grouping(self):
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        command = "        run: python -B tools/engineering_gate/test_persistence.py PersistenceBindingCommands\n"
        mutations = (
            (workflow.replace("policies, passwords, migration", "policies, passwords", 1), "固定并行组缺失或偏离"),
            (workflow.replace("policies, passwords, migration", "policies, passwords, passwords, migration", 1), "固定并行组缺失或偏离"),
            (workflow.replace(command, "", 1), "并行校准入口遗漏或未登记"),
            (workflow.replace(command, command + "      - name: 错误重复\n        if: matrix.group == 'persistence-routing'\n" + command, 1), "并行校准入口重复"),
            (workflow.replace("if: matrix.group == 'methods'", "if: matrix.group == 'roles'", 1), "校准入口错组或被跳过"),
            (workflow.replace("if: matrix.group == 'http'", "if: matrix.group == 'http' && false", 1), "校准入口错组或被跳过"),
            (workflow.replace("    timeout-minutes: 20\n    strategy:", "    timeout-minutes: 0\n    strategy:", 1), "执行须有唯一正整数预算"),
        )
        for changed, diagnostic in mutations:
            with self.subTest(diagnostic=diagnostic):
                self.assertNotEqual(changed, workflow, "反例必须真实改变目标输入")
                with self.assertRaisesRegex(AssertionError, diagnostic):
                    self.assert_workflow(changed)
        self.assert_workflow(workflow)

    def test_missing_or_misowned_preparation_is_rejected_then_original_repairs(self):
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        mutations = (
            (workflow.replace("calibrate_access_state.py --prepare", "calibrate_access_state.py --environment", 1), "访问状态依赖与镜像须唯一准备"),
            (workflow.replace("if: matrix.group == 'credentials' || matrix.group == 'policies' || matrix.group == 'passwords'", "if: matrix.group == 'credentials'", 1), "访问状态准备须覆盖三个访问组"),
            (workflow.replace("calibrate_persistence.py --prepare", "calibrate_persistence.py --environment", 1), "持久化依赖须唯一准备"),
            (workflow.replace("if: matrix.group == 'migration' || matrix.group == 'persistence-routing' || matrix.group == 'persistence-state'",
                              "if: matrix.group == 'migration'", 1), "迁移准备须覆盖迁移及两个持久化组"),
            (workflow.replace("if: matrix.group == 'persistence-routing' || matrix.group == 'persistence-state'",
                              "if: matrix.group == 'persistence-routing'", 1), "持久化准备须覆盖两个固定组"),
        )
        for changed, diagnostic in mutations:
            with self.subTest(diagnostic=diagnostic):
                self.assertNotEqual(changed, workflow, "反例必须真实改变准备输入")
                with self.assertRaisesRegex(AssertionError, diagnostic):
                    self.assert_workflow(changed)
        self.assert_workflow(workflow)

    def test_real_scan_and_bounded_preparation_are_registered_without_loosening_regular_budget(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        check = catalog["checks"]["dependency-risk-mechanism"]
        self.assertTrue(check["implemented"], "真实基础告警不能仍只登记配置或历史结果")
        self.assertEqual(check["argv"], ["@python", "-B", "-m", "unittest", "discover", "-s", "tools/engineering_gate", "-p", "test_advisory_runtime.py"], "基础扫描命令偏离")
        self.assertEqual(check["environment_argv"], ["@python", "-B", "tools/engineering_gate/calibrate_advisory.py", "--environment"], "基础扫描环境入口偏离")
        self.assertTrue(check["requires_test_count"])
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        self.assertIn("  advisory-data:\n", workflow, "缺少原生资料准备")
        preparation, regular = workflow.split("  advisory-data:\n", 1)[1].split("  mechanism-checks:\n", 1)
        self.assertIn("    needs: scope-checks\n", preparation, "校准选择缺少前置核对")
        self.assertIn("    if: needs.scope-checks.outputs.maintenance-required == 'true'\n", preparation,
                      "校准条件必须来自本次差异")
        self.assertIn('ci-plan --base "$TASK_EVENT_BASE"', workflow, "CI 不得复用长期任务历史范围")
        self.assert_bounded_budget(preparation)
        self.assertIn("    needs: advisory-data", regular, "资料准备与校准缺少依赖")
        self.assert_bounded_budget(regular)
        self.assertIn("calibrate_advisory.py --prepare", preparation)
        self.assertIn("      - name: 仅保存成功准备的原生缓存\n        if: success()", preparation, "准备失败不能写入缓存")
        self.assertEqual(re.findall(r"^          fail-on-cache-miss: (true|false)$", regular, re.M), ["true"], "资料缓存缺失不能放行")
        self.assertIn("run: python -B -m unittest discover -s tools/engineering_gate -p 'test_advisory_runtime.py'", regular, "CI 基础扫描命令偏离")
        self.assertNotIn("continue-on-error:", workflow)


if __name__ == "__main__":
    unittest.main()
