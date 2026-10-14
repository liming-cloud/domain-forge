"""原生 SDK 导出、真实接收和故障恢复反例，不用配置存在替代运行事实。"""

import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


HERE = pathlib.Path(__file__).resolve().parent


class ObservationCommands(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="domain-forge-observation-test-")
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name)
        for name in ("ObservationFixture.java", "ObservationTransport.java"):
            shutil.copyfile(HERE / name, self.root / name)

    def command(self, code=None):
        result = subprocess.run([sys.executable, "-B", str(HERE / "calibrate_observation.py"), "--fixtures", str(self.root)],
                                text=True, capture_output=True, timeout=60)
        self.assertIn(result.returncode, (0, 1), result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if code is None else 1, report)
        if code:
            self.assertIn(code, [item["code"] for item in report["diagnostics"]], report)
        return report

    def violation_and_repair(self, filename, old, new, code):
        path = self.root / filename
        clean = path.read_text()
        self.assertIn(old, clean)
        path.write_text(clean.replace(old, new, 1))
        self.command(code)
        path.write_text(clean)
        self.command()

    def test_actual_signals_results_fault_isolation_and_new_data_recovery(self):
        report = self.command()
        self.assertFalse(report["product_verified"])
        self.assertGreater(report["observed"]["failureHealth"]["failedExports"], 0)

    def test_rejection_metrics_and_broken_parent_context_are_rejected(self):
        self.violation_and_repair("ObservationFixture.java", 'AttributeKey.stringKey("outcome"), actual));',
                                  'AttributeKey.stringKey("outcome"), "completed"));', "SG_OBSERVATION_RESULTS")
        self.violation_and_repair("ObservationFixture.java", 'parent.makeCurrent()',
                                  'io.opentelemetry.context.Context.root().makeCurrent()', "SG_OBSERVATION_CORRELATION")

    def test_export_failure_must_not_change_business_or_hide_degradation(self):
        self.violation_and_repair("ObservationFixture.java", 'return actual;',
                                  'return transport.failedExports() > 0 ? "unavailable" : actual;', "SG_OBSERVATION_BUSINESS")
        self.violation_and_repair("ObservationFixture.java", '"observationAvailable",\n        transport.available()',
                                  '"observationAvailable", true', "SG_OBSERVATION_HEALTH")

    def test_raw_export_header_secret_and_unbounded_input_dimension_are_rejected(self):
        self.violation_and_repair("ObservationTransport.java", '.header("Content-Type", "application/json")',
                                  '.header("X-Secret", "observation-calibration-secret").header("Content-Type", "application/json")',
                                  "SG_OBSERVATION_SECRET")
        self.violation_and_repair("ObservationFixture.java", 'results.add(1, Attributes.of(AttributeKey.stringKey("outcome"), actual));',
                                  'results.add(1, Attributes.builder().put("outcome", actual).put("request", untrusted).build());',
                                  "SG_OBSERVATION_SECRET")

    def test_renamed_orphan_span_and_exception_secret_cannot_bypass_validation(self):
        self.violation_and_repair("ObservationFixture.java", 'tracer.spanBuilder("dependency").startSpan()',
                                  'tracer.spanBuilder("background").setNoParent().startSpan()', "SG_OBSERVATION_CORRELATION")
        self.violation_and_repair("ObservationFixture.java", 'return actual;',
                                  'throw new IllegalStateException(untrusted);', "SG_OBSERVATION_SECRET")

    def test_basic_wrapped_secret_and_stale_recovery_log_are_rejected(self):
        self.violation_and_repair("ObservationTransport.java", '.header("Content-Type", "application/json")',
            '.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("user:observation-calibration-secret".getBytes())).header("Content-Type", "application/json")',
            "SG_OBSERVATION_SECRET")
        path = self.root / "ObservationTransport.java"
        clean = path.read_text()
        mutated = clean.replace('return new LogRecordExporter() {',
            'return new LogRecordExporter() { private Collection<LogRecordData> first;')
        mutated = mutated.replace('public CompletableResultCode export(Collection<LogRecordData> logs) {',
            'public CompletableResultCode export(Collection<LogRecordData> logs) { if (first == null) first = java.util.List.copyOf(logs); if (failedExports() > 0) logs = first;')
        self.assertNotEqual(clean, mutated)
        path.write_text(mutated)
        self.command("SG_OBSERVATION_RECOVERY")
        path.write_text(clean)
        self.command()


if __name__ == "__main__":
    unittest.main()
