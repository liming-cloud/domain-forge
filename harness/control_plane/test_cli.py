"""用真实进程验证薄入口，并用隔离 Git 删除反例验证迁移责任。"""

import json
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import control_plane
import test_control_plane as fixtures


ROOT = Path(__file__).resolve().parents[2]


class ThinEntryCommands(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="domain-forge-cli-")
        self.addCleanup(self.temp.cleanup)
        self.cwd = Path(self.temp.name).resolve()
        self.root = self.cwd / "仓库 with spaces"
        (self.root / "harness/control_plane").mkdir(parents=True)
        (self.root / "harness/product_gate").mkdir()
        shutil.copy2(ROOT / "dev", self.root / "dev")
        shutil.copy2(ROOT / "harness/cli.py", self.root / "harness/cli.py")
        probe = '''import json, os, sys, time
if os.environ.get("CLI_WAIT"):
    print("ready", flush=True)
    time.sleep(30)
else:
    print(json.dumps({"argv": sys.argv[1:], "cwd": os.getcwd(), "pid": os.getpid(),
                      "stdin": sys.stdin.read(), "environment": os.environ.get("CLI_MARKER")}))
    print("native diagnostic", file=sys.stderr)
    raise SystemExit(int(os.environ.get("CLI_EXIT", "0")))
'''
        for entry in ("control_plane/control_plane.py", "product_gate/verify.py"):
            (self.root / "harness" / entry).write_text(probe)

    def launch(self, args, direct=False, environment=None):
        argv = ([sys.executable, "-B", str(self.root / "harness/cli.py")] if direct else [str(self.root / "dev")])
        env = dict(os.environ, CLI_MARKER="保持环境")
        env.update(environment or {})
        process = subprocess.Popen([*argv, *args], cwd=self.cwd, env=env,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        stdout, stderr = process.communicate("input with 中文", timeout=10)
        return process, json.loads(stdout), stderr

    def test_all_control_commands_and_repeated_refs_are_forwarded(self):
        for command in ("check", "index", "context", "route", "ci-plan", "run", "delivery", "verify-receipt"):
            with self.subTest(command=command):
                args = ["--root", "相对目录 with spaces", command, "--task", "x", "--ref", "A#一", "--ref", "B#二"]
                process, result, _ = self.launch(args)
                self.assertEqual(process.returncode, 0)
                self.assertEqual(result["argv"], args)
                self.assertEqual(result["cwd"], str(self.cwd))

    def test_product_default_root_does_not_change_relative_arguments(self):
        args = ["--repository", "relative repo", "--tools", "工具", "--environment"]
        process, result, _ = self.launch(["product", *args])
        self.assertEqual(process.returncode, 0)
        self.assertEqual(result["argv"], ["--project", str(self.root), *args])
        self.assertEqual(result["cwd"], str(self.cwd))

    def test_explicit_product_project_follows_default_without_reparsing(self):
        for project in (["--project", "其他项目"], ["--project=relative/project"]):
            with self.subTest(project=project):
                _, result, _ = self.launch(["product", *project, "--check", "all"])
                self.assertEqual(result["argv"], ["--project", str(self.root), *project, "--check", "all"])

    def test_python_entry_preserves_streams_environment_and_nonzero_exit(self):
        for direct in (False, True):
            with self.subTest(direct=direct):
                process, result, stderr = self.launch(["run"], direct=direct, environment={"CLI_EXIT": "7"})
                self.assertEqual(process.returncode, 7)
                self.assertEqual(result["pid"], process.pid, "进程替换不应引入等待包装进程")
                self.assertEqual(result["stdin"], "input with 中文")
                self.assertEqual(result["environment"], "保持环境")
                self.assertEqual(stderr, "native diagnostic\n")

    def test_interrupts_match_direct_entry_and_do_not_become_success(self):
        for sig in (signal.SIGINT, signal.SIGTERM):
            codes = []
            for entry in ([sys.executable, str(self.root / "harness/control_plane/control_plane.py")],
                          [str(self.root / "dev")], [sys.executable, "-B", str(self.root / "harness/cli.py")]):
                process = subprocess.Popen(entry, cwd=self.cwd, env=dict(os.environ, CLI_WAIT="1"),
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
                try:
                    self.assertTrue(select.select([process.stdout], [], [], 10)[0], "原生入口未就绪")
                    self.assertEqual(process.stdout.readline(), b"ready\n")
                    os.killpg(process.pid, sig)
                    process.communicate(timeout=10)
                    codes.append(process.returncode)
                finally:
                    if process.poll() is None:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.communicate()
            self.assertNotEqual(codes[0], 0)
            self.assertEqual(codes, [codes[0]] * 3)

    def test_real_check_is_equivalent_from_external_directory(self):
        reports = []
        for entry in ([str(ROOT / "dev")], [sys.executable, "-B", str(ROOT / "harness/cli.py")],
                      [sys.executable, "-B", str(ROOT / "harness/control_plane/control_plane.py")]):
            result = subprocess.run([*entry, "check"], cwd=self.cwd, capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
            reports.append(json.loads(result.stdout))
        self.assertEqual(reports, [reports[0]] * 3)

    def test_real_parser_rejects_unknown_missing_and_misplaced_arguments(self):
        for args in ([], ["unknown"], ["context"], ["check", "--root", str(ROOT)],
                     ["product", "--unknown"], ["product", "--project"]):
            with self.subTest(args=args):
                result = subprocess.run([str(ROOT / "dev"), *args], cwd=self.cwd,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertIn("error:", result.stderr)

    def test_real_relative_product_override_uses_callers_directory(self):
        for option in (["--project", "missing project"], ["--project=missing project"]):
            result = subprocess.run([str(ROOT / "dev"), "product", *option, "--environment"],
                                    cwd=self.cwd, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            report = json.loads(result.stdout)
            self.assertFalse(report["ok"])
            self.assertIn("missing project", result.stdout)

    def test_help_keeps_product_mode_visible(self):
        result = subprocess.run([str(ROOT / "dev"), "--help"], cwd=self.cwd,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0)
        self.assertIn("dev product --help", result.stdout)
        for command in ("check", "index", "context", "route", "ci-plan", "run", "delivery", "verify-receipt"):
            self.assertIn(command, result.stdout)
        result = subprocess.run([str(ROOT / "dev"), "product", "--help"], cwd=self.cwd,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0)
        self.assertIn("--project", result.stdout)
        self.assertIn("--prepare-data", result.stdout)


class MigrationResponsibilityCommands(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ControlPlaneCommands("test_ci_plan_uses_event_diff_not_long_running_task_history")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        fixture = self.fixture
        fixture.maintenance_policy()
        fixture.catalog["checks"]["product-probe"] = {
            "kind": "automatic", "implemented": True, "stage": "product",
            "mechanism_checks": ["doc-integrity"], "reason": "隔离选择反例，不执行产品",
            "argv": ["@python", "-c", "print('fixture')"]}
        fixture.catalog["product_ci"] = {"inputs": ["harness/probe.py"], "checks": ["product-probe"]}
        fixture.write("tools/probe.py", "# 原工具输入\n")
        fixture.save_catalog()
        fixture.git("add", ".")
        fixture.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                    "commit", "--quiet", "-m", "legacy input")
        self.base = fixture.git("rev-parse", "HEAD").strip()

    def test_genuine_deleted_legacy_path_keeps_calibration_and_product_responsibility(self):
        fixture = self.fixture
        (fixture.root / "tools/probe.py").unlink()
        code, report = fixture.command("ci-plan", "--base", self.base)
        self.assertEqual(code, 0, report)
        self.assertIn("tools/probe.py", report["changed_paths"])
        self.assertNotIn("harness/probe.py", report["changed_paths"])
        self.assertTrue(report["maintenance_required"])
        self.assertTrue(report["product_required"])
        fixture.task["base"] = self.base
        fixture.save_task()
        code, report = fixture.command("route", "--task", "fixture")
        self.assertEqual(code, 0, report)
        self.assertIn("probe-tests", report["required_checks"])

    def test_live_legacy_input_is_not_accepted_as_a_migration_alias(self):
        self.fixture.write("tools/probe.py", "# 旧路径仍存活\n")
        self.fixture.expect_failure("CP_UNKNOWN_FILE", "ci-plan", "--base", self.base)

    def test_non_git_missing_legacy_path_is_not_a_deletion(self):
        plane = control_plane.ControlPlane(self.fixture.root)
        with self.assertRaises(control_plane.Rejected) as rejected:
            plane.classify("tools/never-tracked.py", self.base)
        self.assertEqual(rejected.exception.diagnostic["code"], "CP_UNKNOWN_FILE")

    def test_recreated_legacy_file_is_rejected_after_deletion_was_read(self):
        path = self.fixture.root / "tools/probe.py"
        path.unlink()
        plane = control_plane.ControlPlane(self.fixture.root)
        self.assertEqual(plane.responsibility_path("tools/probe.py", self.base), "harness/probe.py")
        path.write_text("# 重新创建不能沿用删除证据\n")
        with self.assertRaises(control_plane.Rejected):
            plane.classify("tools/probe.py", self.base)

    def test_each_thin_entry_is_bound_to_snapshot_and_full_check_selection(self):
        fixture = self.fixture
        (fixture.root / "tools/probe.py").unlink()
        paths = ("dev", "harness/cli.py")
        fixture.catalog["classifiers"].append({"glob": "dev", "purpose": "governance", "scopes": ["documentation"]})
        fixture.catalog["maintenance"]["inputs"].extend(paths)
        fixture.catalog["product_ci"]["inputs"].extend(paths)
        fixture.save_catalog()
        fixture.git("add", ".")
        fixture.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                    "commit", "--quiet", "-m", "entry responsibility")
        base = fixture.git("rev-parse", "HEAD").strip()
        for path in paths:
            with self.subTest(path=path):
                before = control_plane.input_snapshot(fixture.root)[0]
                fixture.write(path, "# 新入口输入\n")
                after, inputs = control_plane.input_snapshot(fixture.root)
                self.assertIn(path, inputs)
                self.assertNotEqual(before, after)
                code, report = fixture.command("ci-plan", "--base", base)
                self.assertEqual(code, 0, report)
                self.assertTrue(report["maintenance_required"])
                self.assertTrue(report["product_required"])
                (fixture.root / path).unlink()


if __name__ == "__main__":
    unittest.main()
