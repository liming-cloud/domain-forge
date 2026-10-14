"""实际 Boot 装配及 Nacos 配置的有效正反例；只管理本轮私有容器。"""

import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


HERE = pathlib.Path(__file__).resolve().parent


class RuntimeFixtures(unittest.TestCase):
    mode = "assembly"

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="domain-forge-runtime-test-")
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name)
        shutil.copyfile(HERE / "RuntimeFixture.java", self.root / "RuntimeFixture.java")

    def command(self, code=None):
        process = subprocess.run([sys.executable, "-B", str(HERE / "calibrate_runtime.py"), "--mode", self.mode,
                                  "--fixtures", str(self.root)], text=True, capture_output=True, timeout=180)
        self.assertIn(process.returncode, (0, 1), process.stderr)
        report = json.loads(process.stdout)
        self.assertEqual(process.returncode, 0 if code is None else 1, report)
        if code:
            self.assertIn(code, [item["code"] for item in report["diagnostics"]], report)
        return report

    def violation_and_repair(self, old, new, code):
        path = self.root / "RuntimeFixture.java"
        clean = path.read_text()
        self.assertIn(old, clean)
        path.write_text(clean.replace(old, new, 1))
        self.command(code)
        path.write_text(clean)
        self.command()


class RuntimeAssemblyCommands(RuntimeFixtures):
    def test_actual_default_optional_override_conflict_and_partial_availability(self):
        result = self.command()
        self.assertFalse(result["product_verified"])
        self.assertEqual(result["observed"]["override"]["count"], 1)

    def test_missing_input_and_silent_selection_fallback_are_rejected_then_repaired(self):
        self.violation_and_repair('!target.isBlank() && ', '', "SG_RUNTIME_AVAILABILITY")
        self.violation_and_repair('implementation.equals("default")', 'true', "SG_RUNTIME_AVAILABILITY")

    def test_default_must_back_off_for_user_override_and_disabled_capability(self):
        self.violation_and_repair('@ConditionalOnMissingBean(RuntimeFixture.Connector.class)', '', "SG_RUNTIME_OVERRIDE")
        self.violation_and_repair('@ConditionalOnProperty(name = "probe.enabled", havingValue = "true")', '', "SG_RUNTIME_OPTIONAL")


class RuntimeNacosCommands(RuntimeFixtures):
    mode = "nacos"

    def test_real_config_authority_isolation_updates_disconnect_and_recovery(self):
        result = self.command()
        self.assertEqual(result["observed"]["receivedDuringFailure"], "alpha-3")
        self.assertEqual(result["observed"]["applicationFailure"], "application-unavailable")
        self.assertTrue(result["observed"]["disconnectedFetchUnavailable"])

    def test_local_value_cannot_replace_authoritative_applied_value(self):
        self.violation_and_repair('return value;', 'return "local-default";', "SG_RUNTIME_CONFIGURATION")

    def test_notification_does_not_prove_application_update_succeeded(self):
        self.violation_and_repair('Files.exists(Path.of(failureFile))', 'false', "SG_RUNTIME_CONFIGURATION")

    def test_missing_actual_fault_injection_cannot_pass(self):
        import os
        from types import SimpleNamespace
        from unittest.mock import patch
        import calibrate_runtime
        from calibrate_source import ProbeFailure
        original = calibrate_runtime.calibrate_flyway.docker

        def no_pause(*args, **kwargs):
            return "" if args[0] in {"pause", "unpause"} else original(*args, **kwargs)

        args = SimpleNamespace(mode="nacos", fixtures=str(self.root),
                               runtime_tools=os.environ["DOMAIN_FORGE_GATE_RUNTIME_TOOLS"])
        with patch.object(calibrate_runtime.calibrate_flyway, "docker", no_pause):
            with self.assertRaises(ProbeFailure) as failure:
                calibrate_runtime.probe(args)
        self.assertEqual(failure.exception.diagnostic["code"], "SG_RUNTIME_FAULT")
        self.command()


class RuntimeQualityChecks(unittest.TestCase):
    def test_all_new_observer_and_fixture_sources_meet_shared_baseline(self):
        files = [str(HERE / name) for name in (
            "HttpConsumerFixture.java", "HttpConsumerInventory.java", "RuntimeFixture.java", "RuntimeInventory.java",
            "ObservationFixture.java", "ObservationTransport.java", "ObservationInventory.java")]
        for action in ("comments", "method-presence", "format", "complexity"):
            argv = [sys.executable, "-B", str(HERE / "calibrate_source.py"), action]
            if action in ("comments", "complexity"):
                argv += ["--purpose", "production"]
            result = subprocess.run([*argv, *files], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(set(json.loads(result.stdout)["scanned"]), set(files))

    def test_runtime_environments_use_control_plane_result_contract(self):
        sys.path.insert(0, str(HERE.parent / "control_plane"))
        from control_plane import ControlPlane
        plane = ControlPlane(HERE.parents[1])
        representatives = {}
        for name in ("runtime-mechanism", "runtime-nacos-tests", "observation-tests", "runtime-source-quality-tests"):
            definition = plane.catalog["checks"][name]
            representatives[tuple(definition["environment_argv"])] = name
        result = plane.execution_environment(sorted(representatives.values()))
        self.assertEqual(set(result["checks"]), set(representatives.values()))
        self.assertTrue(all(result["checks"].values()))


if __name__ == "__main__":
    unittest.main()
