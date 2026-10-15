"""原生隔离 Git 与 GitHub 协议反例；不声称真实远端 CI 或人工验收。"""

import json
import copy
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

import test_control_plane as fixtures
from control_plane import ControlPlane
import delivery_evidence as delivery


ENTRY = Path(__file__).with_name("control_plane.py")


class DeliveryEntryTests(unittest.TestCase):
    def test_product_preparation_preserves_native_logs_and_exit_status(self):
        """执行真实工作流 shell；仅准备工具与 jq 输出是协议模拟，不代表扫描通过。"""
        workflow = (ENTRY.parents[2] / delivery.WORKFLOW).read_text()
        product = workflow.split("  product-checks:\n", 1)[1].split("  advisory-data:\n", 1)[0]
        prepare = product.split("      - name: 准备原生漏洞资料（环境准备不代表产品扫描）\n", 1)[1]
        script = textwrap.dedent(prepare.split("        run: |\n", 1)[1].split("      - name:", 1)[0])
        upload = product.split("      - name: 保存产品验证证据\n", 1)[1].split("      - uses:", 1)[0]
        self.assertIn("if: always()", upload)
        patterns = textwrap.dedent(upload.split("          path: |\n", 1)[1]).splitlines()
        for code in (0, 1):
            with self.subTest(exit_code=code), tempfile.TemporaryDirectory(prefix="domain-forge-preparation-") as temp:
                root = Path(temp)
                binary = root / "bin"
                binary.mkdir()
                native = root / "native-evidence" / "advisory-preparation.log"
                native.parent.mkdir()
                native.write_text("native failure detail must survive runner cleanup\n")
                payload = {"ok": code == 0}
                if code:
                    payload["diagnostics"] = [{"code": "PG_PREPARATION", "log": str(native)}]
                else:
                    payload["execution"] = {"log": str(native)}
                python = binary / "python"
                python.write_text("#!" + sys.executable + "\nimport sys\nprint(" + repr(json.dumps(payload)) +
                                  ")\nsys.exit(" + str(code) + ")\n")
                python.chmod(0o755)
                jq = binary / "jq"
                jq.write_text("#!" + sys.executable + "\nimport json,sys\nd=json.load(open(sys.argv[-1]))\n" +
                              "print(d.get('execution', {}).get('log') or d.get('diagnostics', [{}])[0].get('log') or '')\n")
                jq.chmod(0o755)
                env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                           RUNNER_TEMP=str(root), DOMAIN_FORGE_GATE_ADVISORY_DATA=str(root / "data"))
                result = subprocess.run(["bash", "-e", "-c", script], cwd=root, env=env,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, code, result.stderr)
                retained = []
                for pattern in patterns:
                    pattern = pattern.strip().replace("${{ runner.temp }}", str(root))
                    if not pattern or "${{" in pattern:
                        continue
                    path = Path(pattern)
                    retained.extend(path.rglob("*") if path.is_dir() else [path])
                self.assertTrue(any(path.is_file() and path.read_bytes() == native.read_bytes()
                                    for path in retained), "上传路径必须包含原生准备日志，而非仅含摘要或临时路径")

    def test_missing_query_is_explicitly_rejected_without_network_or_acceptance(self):
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "delivery", "--task",
                                 "control-plane-verification"], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 1, result.stderr)
        report = json.loads(result.stdout)
        self.assertFalse(report["ok"])
        self.assertFalse(report["human_acceptance"])
        self.assertIn("CP_DELIVERY_REQUIRED", [item["code"] for item in report["diagnostics"]])


class DeliveryObjectsTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ControlPlaneCommands()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.fixture.catalog["classifiers"].append({"glob": ".github/workflows/*.yml",
                                                    "purpose": "governance", "scopes": ["documentation"]})
        self.fixture.save_catalog()
        self.fixture.write(delivery.WORKFLOW, (ENTRY.parents[2] / delivery.WORKFLOW).read_text())
        self.fixture.git("remote", "add", "origin", "https://github.com/example/delivery.git")
        self.fixture.render_indices()
        self.commit("feat: 隔离功能对象")
        self.sha = self.fixture.git("rev-parse", "HEAD").strip()
        self.branch = self.fixture.git("branch", "--show-current").strip()
        temp = tempfile.TemporaryDirectory(prefix="domain-forge-delivery-query-")
        self.addCleanup(temp.cleanup)
        self.query_path = Path(temp.name) / "query.json"
        self.query = dict(task="fixture", functional_sha=self.sha, branch=self.branch, functional_run_id=31)
        self.remote = dict(ref="refs/heads/" + self.branch, object={"type": "commit", "sha": self.sha})
        self.runs = {31: self.run_object(31, self.sha)}
        self.jobs = {31: self.job_objects(31, self.sha)}
        self.requests = []
        self.hook = None

    def commit(self, message):
        self.fixture.git("add", ".")
        self.fixture.git("-c", "user.name=Delivery Fixture", "-c", "user.email=fixture@example.invalid",
                         "commit", "--quiet", "-m", message)

    def run_object(self, identifier, sha):
        return dict(id=identifier, run_attempt=1, head_sha=sha, head_branch=self.branch,
                    repository={"full_name": "example/delivery"}, head_repository={"full_name": "example/delivery"},
                    path=delivery.WORKFLOW, event="push", status="completed", conclusion="success")

    def job_objects(self, identifier, sha):
        # 预期来自 CI 文件的三个实际 job，测试响应为协议模拟，不代表 GitHub 执行。
        return [dict(id=identifier * 100, run_id=identifier, head_sha=sha,
                     name="文档一致性与本次变更范围（非任务验收）", status="completed", conclusion="success",
                     steps=[dict(name=name, status="completed", conclusion="success") for name in
                            ["Set up job", "文档结构与控制面有效反例", "由事件差异选择校准，不读取长期任务的历史范围"]]),
                dict(id=identifier * 100 + 1, run_id=identifier, head_sha=sha,
                     name="原生依赖告警资料准备（独立预算，非产品扫描）", conclusion="skipped"),
                dict(id=identifier * 100 + 2, run_id=identifier, head_sha=sha,
                     name="隔离门禁校准 / （非整套验收）", conclusion="skipped")]

    def inspect(self, diagnostic=None):
        self.query_path.write_text(json.dumps(self.query))
        owner = self

        class ProtocolFixture:
            def __init__(self, root, repository):
                self.repository = repository

            def get(self, path):
                owner.requests.append(path)
                if owner.hook:
                    owner.hook(path)
                if path.startswith("git/ref/heads/"):
                    return copy.deepcopy(owner.remote)
                identifier = int(path.split("/")[2])
                if "jobs?" in path:
                    return dict(total_count=len(owner.jobs[identifier]), jobs=copy.deepcopy(owner.jobs[identifier]))
                return copy.deepcopy(owner.runs[identifier])

        result = delivery.inspect_delivery(ControlPlane(self.root), "fixture", self.query_path, ProtocolFixture)
        self.assertEqual(result["ok"], diagnostic is None, result)
        self.assertFalse(result["human_acceptance"])
        self.assertFalse(result["delivery_complete"])
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in result["diagnostics"]], result)
        return result

    def test_native_candidate_and_protocol_success_remain_object_checks_only(self):
        before = self.fixture.git("status", "--porcelain=v1")
        result = self.inspect()
        self.assertEqual(result["technical_status"], "candidate-checked")
        self.assertEqual(result["functional_sha"], self.sha)
        self.assertEqual(result["runs"][0]["attempt"], 1)
        self.assertEqual(before, self.fixture.git("status", "--porcelain=v1"))
        self.assertEqual(self.fixture.git("rev-parse", "HEAD").strip(), self.sha)

    def test_unexpanded_skipped_matrix_name_supports_candidate_and_record_ci(self):
        # 真实 GitHub 的跳过 job 保留矩阵表达式；这里只重放该协议形态。
        name = "隔离门禁校准 / ${{ matrix.group }}（非整套验收）"
        self.jobs[31][-1]["name"] = name
        self.assertEqual(self.inspect()["technical_status"], "candidate-checked")
        self.closing()
        self.jobs[32][-1]["name"] = name
        self.assertEqual(self.inspect()["technical_status"], "closing-checked")

    def test_unexpanded_matrix_cannot_hide_required_execution_or_wrong_status(self):
        original = copy.deepcopy(self.jobs[31][-1])
        name = "隔离门禁校准 / ${{ matrix.group }}（非整套验收）"
        for status in ("success", "failure", "cancelled"):
            self.jobs[31][-1] = dict(original, name=name, conclusion=status)
            self.inspect("CP_DELIVERY_CI_COVERAGE")
        self.jobs[31][-1] = dict(original, name="隔离门禁校准 / unknown（非整套验收）")
        self.inspect("CP_DELIVERY_CI_COVERAGE")
        self.jobs[31][-1] = dict(original, name=name)
        self.runs[31]["event"] = "workflow_dispatch"
        self.inspect("CP_DELIVERY_CI_COVERAGE")

    def test_dirty_and_hidden_tracked_change_are_rejected_then_repaired(self):
        path = self.root / "README.md"
        old = path.read_bytes()
        path.write_bytes(old + b"\nchanged\n")
        self.inspect("CP_DELIVERY_DIRTY")
        self.fixture.git("update-index", "--assume-unchanged", "README.md")
        self.inspect("CP_DELIVERY_DIRTY")
        self.fixture.git("update-index", "--no-assume-unchanged", "README.md")
        path.write_bytes(old)
        self.inspect()

    def test_remote_run_input_and_latest_attempt_mismatch_reject_then_repair(self):
        original = copy.deepcopy(self.runs[31])
        for field, value in [("head_sha", "a" * 40), ("head_branch", "other"), ("path", "other.yml"),
                             ("event", "pull_request"), ("repository", {"full_name": "attacker/fork"}),
                             ("head_repository", {"full_name": "attacker/fork"})]:
            with self.subTest(field=field):
                self.runs[31] = dict(original, **{field: value})
                self.inspect("CP_DELIVERY_CI_INPUT")
        self.runs[31] = original
        self.remote["object"]["sha"] = "a" * 40
        self.inspect("CP_DELIVERY_REMOTE")
        self.remote["object"]["sha"] = self.sha
        self.runs[31]["conclusion"] = "failure"
        self.inspect("CP_DELIVERY_CI_FAILED")
        self.runs[31]["conclusion"] = "success"
        self.inspect()

    def test_green_run_cannot_hide_empty_missing_duplicate_failed_or_skipped_execution(self):
        original = copy.deepcopy(self.jobs[31])
        for jobs, code in [([], "CP_DELIVERY_CI_COVERAGE"), (original[1:], "CP_DELIVERY_CI_COVERAGE"),
                           (original + [original[0]], "CP_DELIVERY_CI_COVERAGE")]:
            self.jobs[31] = jobs
            self.inspect(code)
        for status in ("skipped", "failure", "cancelled", "neutral"):
            self.jobs[31] = copy.deepcopy(original)
            self.jobs[31][0]["steps"][1]["conclusion"] = status
            self.inspect("CP_DELIVERY_CI_FAILED")
        self.jobs[31] = copy.deepcopy(original)
        self.jobs[31][0]["steps"] = []
        self.inspect("CP_DELIVERY_CI_COVERAGE")
        self.jobs[31] = original
        self.inspect()

    def test_during_query_remote_rerun_git_and_query_changes_invalidate_results(self):
        original = copy.deepcopy(self.runs[31])
        def rerun(path):
            if path == "actions/runs/31" and self.requests.count(path) > 1:
                self.runs[31]["run_attempt"] = 2
        self.hook = rerun
        self.inspect("CP_DELIVERY_CI_CHANGED")
        self.hook, self.runs[31], self.requests = None, original, []
        def drift(path):
            if "jobs?" in path:
                self.query_path.write_text("{}")
        self.hook = drift
        self.inspect("CP_DELIVERY_CHANGED")
        self.hook = None
        self.inspect()

    def closing(self):
        self.fixture.task["acceptance"] = dict(actor="用户", date="2026-10-14", source="WORK#交付",
                                               functional_sha=self.sha, scope="隔离夹具")
        self.fixture.task["closing"] = dict(status="pending", reason="尚未核对实际远端")
        self.fixture.save_task()
        self.fixture.render_indices()
        self.commit("doc: 隔离验收记录")
        head = self.fixture.git("rev-parse", "HEAD").strip()
        self.query["record_run_id"] = 32
        self.remote["object"]["sha"] = head
        self.runs[32] = self.run_object(32, head)
        self.jobs[32] = self.job_objects(32, head)

    def test_record_only_closeout_preserves_human_boundary_and_failed_closeout(self):
        self.closing()
        result = self.inspect()
        self.assertEqual(result["technical_status"], "closing-checked")
        self.assertEqual(result["acceptance_record"]["proof_scope"], "source-association-only")
        self.jobs[32][0]["conclusion"] = "failure"
        result = self.inspect("CP_DELIVERY_CI_FAILED")
        self.assertEqual(result["acceptance_record"]["functional_sha"], self.sha)
        self.jobs[32][0]["conclusion"] = "success"
        self.inspect()

    def test_record_cannot_include_code_task_contract_or_second_record_commit(self):
        self.closing()
        self.fixture.write("extra.py", "print('not acceptance')\n")
        self.commit("doc: 额外记录")
        self.inspect("CP_DELIVERY_RECORD")
        self.fixture.git("reset", "--soft", "HEAD~2")
        self.commit("doc: 夹带代码")
        self.inspect("CP_DELIVERY_RECORD")

    def test_git_environment_cannot_select_another_repository_or_replace_objects(self):
        with patch.dict(os.environ, {"GIT_DIR": "/missing/other.git", "GIT_WORK_TREE": "/missing/other",
                                     "GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "alias.status",
                                     "GIT_CONFIG_VALUE_0": "!echo secret", "GH_HOST": "attacker.invalid"}):
            self.assertEqual(delivery.git(self.root, "rev-parse", "HEAD").strip(), self.sha)
            self.assertNotIn("GIT_CONFIG_COUNT", delivery.environment())
            self.assertNotIn("GH_HOST", delivery.environment())

    def test_public_run_executes_delivery_adapter_and_preserves_missing_evidence(self):
        self.fixture.write("tools/control_plane/delivery_evidence.py", Path(delivery.__file__).read_text())
        self.fixture.catalog["checks"]["delivery-evidence"] = dict(kind="automatic", implemented=True,
            argv=["@python", "-B", "@entry", "delivery", "--task", "@task"])
        self.fixture.catalog["classifiers"].append(dict(glob=".github/**", purpose="governance", scopes=["documentation"]))
        self.fixture.task["verification"].append(dict(check="delivery-evidence", criterion="真实交付对象"))
        self.fixture.save_catalog()
        self.fixture.save_task()
        self.fixture.render_indices()
        code, report = self.fixture.command("run", "--task", "fixture")
        self.assertEqual(code, 1, report)
        self.assertIn("checks", report, report)
        executed = next(item for item in report["checks"] if item["id"] == "delivery-evidence")
        self.assertEqual(executed["exit_code"], 1)
        self.assertEqual(json.loads(executed["stdout"])["diagnostics"][0]["code"], "CP_DELIVERY_REQUIRED")

    def test_changed_rules_require_all_calibration_jobs_not_only_scope_success(self):
        self.fixture.write("README.md", (self.root / "README.md").read_text() + "\n改变规则。\n")
        self.commit("feat: 规则变化")
        self.sha = self.fixture.git("rev-parse", "HEAD").strip()
        self.query["functional_sha"] = self.remote["object"]["sha"] = self.sha
        self.runs[31]["head_sha"] = self.sha
        for job in self.jobs[31]:
            job["head_sha"] = self.sha
        self.inspect("CP_DELIVERY_CI_COVERAGE")

    def test_replace_ref_cannot_hide_catalog_calibration_changes(self):
        self.fixture.catalog["maintenance"] = {"inputs": ["tools/control_plane/catalog.json"], "checks": ["probe-tests"]}
        self.fixture.catalog["checks"]["probe-tests"] = dict(kind="automatic", implemented=True,
            requires_test_count=True, argv=["@python", "-B", "probe.py"])
        self.fixture.save_catalog()
        self.commit("feat: 校准责任变更")
        self.sha = self.fixture.git("rev-parse", "HEAD").strip()
        self.query["functional_sha"] = self.remote["object"]["sha"] = self.sha
        self.runs[31]["head_sha"] = self.sha
        for job in self.jobs[31]:
            job["head_sha"] = self.sha
        self.inspect("CP_DELIVERY_CI_COVERAGE")
        self.fixture.git("replace", self.fixture.task["base"], self.sha)
        self.inspect("CP_DELIVERY_CI_COVERAGE")
        self.fixture.git("replace", "-d", self.fixture.task["base"])
        self.inspect("CP_DELIVERY_CI_COVERAGE")

    def test_record_contract_change_is_rejected_even_when_only_task_file_changes(self):
        self.closing()
        self.fixture.git("reset", "--soft", "HEAD~1")
        self.fixture.task["consumers"] = ["未授权新消费者"]
        self.fixture.save_task()
        self.commit("doc: 夹带任务合同")
        self.inspect("CP_DELIVERY_RECORD")

    def test_provider_failure_and_malformed_response_do_not_echo_secrets(self):
        secret = "fixture-only-sensitive-value"
        result = subprocess.CompletedProcess([], 1, b"", secret.encode())
        with patch.object(delivery.subprocess, "run", return_value=result):
            with self.assertRaises(delivery.Rejected) as error:
                delivery.GitHub(self.root, "example/delivery").get("actions/runs/31")
        self.assertNotIn(secret, str(error.exception))
        with patch.object(delivery, "command", return_value='{"' + secret + '":1,"' + secret + '":2}'):
            with self.assertRaises(delivery.Rejected) as error:
                delivery.GitHub(self.root, "example/delivery").get("actions/runs/31")
        self.assertNotIn(secret, str(error.exception))


class DeliveryProtocolTests(unittest.TestCase):
    def test_unknown_conditions_matrix_exclusions_and_unnamed_run_fail_closed(self):
        original = (ENTRY.parents[2] / delivery.WORKFLOW).read_text()
        for before, after in [
            ("      matrix:\n", "      matrix:\n        exclude:\n          - group: runtime\n"),
            ("    needs: advisory-data", "    needs: advisory-data\n    if: always()"),
            ("      - name: 文档结构与控制面有效反例", "      - run: echo noop"),
            ("        group: [", "        os: [linux]\n        group: ["),
            ("    timeout-minutes: 20", "    continue-on-error: true\n    timeout-minutes: 20")]:
            with self.subTest(after=after), self.assertRaises(delivery.Rejected) as error:
                delivery.workflow_contract(original.replace(before, after, 1), True)
            self.assertEqual(error.exception.diagnostic["code"], "CP_DELIVERY_WORKFLOW")

    def test_full_matrix_protocol_accepts_only_executed_assertions_for_each_group(self):
        workflow = """name: fixture
jobs:
  scope-checks:
    name: scope
    steps:
      - name: scope-check
        run: python scope.py
  advisory-data:
    name: advisory
    needs: scope-checks
    if: needs.scope-checks.outputs.maintenance-required == 'true'
    steps:
      - name: prepare
        run: python prepare.py
  mechanism-checks:
    name: checks / ${{ matrix.group }}
    needs: advisory-data
    strategy:
      matrix:
        group: [source, runtime]
    steps:
      - name: source assertions
        if: matrix.group == 'source'
        run: python source.py
      - name: runtime assertions
        if: matrix.group == 'runtime'
        run: python runtime.py
"""
        expected = delivery.workflow_contract(workflow, True)
        self.assertEqual(set(expected), {"scope", "advisory", "checks / source", "checks / runtime"})
        raw = [("scope", [("scope-check", "success")]), ("advisory", [("prepare", "success")]),
               ("checks / source", [("source assertions", "success"), ("runtime assertions", "skipped")]),
               ("checks / runtime", [("source assertions", "skipped"), ("runtime assertions", "success")])]
        jobs = [dict(id=i, run_id=31, head_sha="a" * 40, name=name, status="completed", conclusion="success",
                     steps=[dict(name=step, status="completed", conclusion=conclusion) for step, conclusion in steps])
                for i, (name, steps) in enumerate(raw, 1)]
        class ProtocolFixture:
            def get(self, path):
                return {"total_count": len(jobs), "jobs": jobs}
        run = dict(id=31, run_attempt=1, head_sha="a" * 40)
        self.assertEqual(len(delivery.verify_jobs(ProtocolFixture(), run, expected)), 4)
        jobs[-1]["steps"][-1]["conclusion"] = "skipped"
        with self.assertRaises(delivery.Rejected) as error:
            delivery.verify_jobs(ProtocolFixture(), run, expected)
        self.assertEqual(error.exception.diagnostic["code"], "CP_DELIVERY_CI_FAILED")

    def test_actual_workflow_has_seventeen_jobs_and_group_specific_assertions(self):
        workflow = (ENTRY.parents[2] / delivery.WORKFLOW).read_text()
        plan = delivery.workflow_contract(workflow, True, True)
        self.assertEqual(len(plan), 17)
        self.assertTrue(all(item["required"] for item in plan.values()))
        runtime = plan["隔离门禁校准 / runtime（非整套验收）"]["steps"]
        self.assertTrue(runtime["runtime-nacos-tests"])
        self.assertFalse(runtime["persistence-mapping-tests"])
        normal = delivery.workflow_contract(workflow, False)
        self.assertEqual([name for name, value in normal.items() if value["required"]],
                         ["文档一致性与本次变更范围（非任务验收）"])

    def test_product_job_and_steps_are_required_only_when_product_applies(self):
        workflow = (ENTRY.parents[2] / delivery.WORKFLOW).read_text()
        expected = delivery.workflow_contract(workflow, False, True)
        run = dict(id=31, run_attempt=1, head_sha="a" * 40)
        valid = [dict(id=index, run_id=31, head_sha=run["head_sha"], name=name,
                      status="completed", conclusion="success",
                      steps=[dict(name=step, status="completed", conclusion="success") for step in spec["steps"]])
                 for index, (name, spec) in enumerate(expected.items()) if spec["required"]]
        jobs = copy.deepcopy(valid)
        class ProductFixture:
            def get(self, path):
                return {"total_count": len(jobs), "jobs": jobs}
        self.assertEqual(len(delivery.verify_jobs(ProductFixture(), run, expected)), 2)
        for fault in ("missing", "skipped", "failure", "missing-step", "skipped-step"):
            jobs = copy.deepcopy(valid)
            product = next(job for job in jobs if job["name"] == "最小真实工程质量与运行验证")
            if fault == "missing":
                jobs.remove(product)
            elif fault in {"skipped", "failure"}:
                product["conclusion"] = fault
            elif fault == "missing-step":
                product["steps"].pop()
            else:
                product["steps"][-1]["conclusion"] = "skipped"
            with self.subTest(fault=fault), self.assertRaises(delivery.Rejected):
                delivery.verify_jobs(ProductFixture(), run, expected)

    def test_legacy_workflow_remains_readable_but_cannot_prove_product_execution(self):
        workflow = (ENTRY.parents[2] / delivery.WORKFLOW).read_text()
        import re
        legacy = re.sub(r"^  product-checks:\n.*?(?=^  advisory-data:)", "", workflow, flags=re.M | re.S)
        plan = delivery.workflow_contract(legacy, True)
        self.assertEqual(len(plan), 16)
        with self.assertRaises(delivery.Rejected) as error:
            delivery.workflow_contract(legacy, True, True)
        self.assertEqual(error.exception.diagnostic["code"], "CP_DELIVERY_WORKFLOW")

    def test_attempt_specific_pagination_rejects_truncation_duplicate_and_other_input(self):
        run = dict(id=31, run_attempt=2, head_sha="a" * 40)
        expected = {"one": {"required": True, "steps": {"assertions": True}},
                    "two": {"required": True, "steps": {"assertions": True}}}
        jobs = [dict(id=i, run_id=31, head_sha="a" * 40, name=name, status="completed", conclusion="success",
                     steps=[dict(name="assertions", status="completed", conclusion="success")])
                for i, name in enumerate(expected, 1)]
        class Paged:
            def __init__(self):
                self.pages = []
                self.truncate = False
            def get(self, path):
                self.pages.append(path)
                page = int(path.rsplit("=", 1)[1])
                return {"total_count": 2, "jobs": [] if self.truncate and page == 2 else jobs[page - 1:page]}
        api = Paged()
        self.assertEqual(delivery.verify_jobs(api, run, expected), jobs)
        self.assertTrue(all("/attempts/2/jobs?" in path for path in api.pages))
        for fault in ("truncated", "duplicate", "wrong-input"):
            original = copy.deepcopy(jobs)
            api.truncate = fault == "truncated"
            if fault == "duplicate":
                jobs[1] = copy.deepcopy(jobs[0])
            if fault == "wrong-input":
                jobs[1]["head_sha"] = "b" * 40
            with self.assertRaises(delivery.Rejected):
                delivery.verify_jobs(api, run, expected)
            jobs[:] = original

    def test_transport_is_get_only_fixed_host_and_never_report_injection(self):
        with patch.object(delivery, "command", return_value='{"id":31}') as read:
            self.assertEqual(delivery.GitHub(Path.cwd(), "example/delivery").get("actions/runs/31"), {"id": 31})
        argv = read.call_args.args[0]
        self.assertEqual(argv[:7], ["gh", "api", "--hostname", "github.com", "--method", "GET", "-H"])
        self.assertEqual(argv[-1], "repos/example/delivery/actions/runs/31")


if __name__ == "__main__":
    unittest.main()
