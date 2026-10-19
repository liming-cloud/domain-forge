"""消费方实际运行的正例、目标违规与新副本修复，不用观察器代替客户端。"""

import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


HERE = pathlib.Path(__file__).resolve().parent


class HttpConsumerCommands(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="domain-forge-consumer-test-")
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name)
        for name in ("HttpConsumerFixture.java", "HttpConsumerInventory.java", "HttpInventory.java"):
            shutil.copyfile(HERE / name, self.root / name)

    def command(self, code=None):
        result = subprocess.run([sys.executable, "-B", str(HERE / "calibrate_http_consumer.py"),
                                 "--fixtures", str(self.root)], text=True, capture_output=True, timeout=90)
        self.assertIn(result.returncode, (0, 1), result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if code is None else 1, report)
        if code:
            self.assertIn(code, [item["code"] for item in report["diagnostics"]], report)
        return report

    def violation_and_repair(self, original, replacement, code):
        path = self.root / "HttpConsumerFixture.java"
        clean = path.read_text()
        self.assertIn(original, clean)
        path.write_text(clean.replace(original, replacement, 1))
        self.command(code)
        path.write_text(clean)
        self.command()

    def test_real_consumer_keeps_http_and_completion_layers(self):
        report = self.command()
        self.assertEqual(report["observed"]["results"]["invalid"]["status"], 200)
        self.assertEqual(report["observed"]["results"]["timeout"]["status"], 0)
        self.assertFalse(report["product_verified"])

    def test_status_only_success_and_lost_pending_state_are_rejected(self):
        self.violation_and_repair('case "LIMIT_REACHED" -> "rejected";',
                                  'case "LIMIT_REACHED" -> "completed";', "SG_HTTP_CONSUMER_RESULT")
        self.violation_and_repair('case "ACCEPTED" -> "pending";',
                                  'case "ACCEPTED" -> "completed";', "SG_HTTP_CONSUMER_RESULT")

    def test_partial_state_and_protocol_bytes_are_preserved(self):
        self.violation_and_repair('value.get("data"), status', 'null, status', "SG_HTTP_CONSUMER_STATE")
        self.violation_and_repair('"PROTOCOL_RESULT", body, status', '"PROTOCOL_RESULT", "{}", status',
                                  "SG_HTTP_CONSUMER_PROTOCOL")

    def test_metrics_and_secret_fanout_cannot_hide_bad_consumer(self):
        self.violation_and_repair('outcomes.merge(result.outcome(), 1, Integer::sum)',
                                  'outcomes.merge("completed", 1, Integer::sum)', "SG_HTTP_CONSUMER_METRICS")
        self.violation_and_repair('outcomes.merge(result.outcome(), 1, Integer::sum);',
                                  'System.out.println("consumer-calibration-secret");\n'
                                  '    outcomes.merge(result.outcome(), 1, Integer::sum);', "SG_HTTP_CONSUMER_SECRET")

    def test_unknown_response_is_not_default_success(self):
        self.violation_and_repair('default -> "unknown";', 'default -> "completed";', "SG_HTTP_CONSUMER_RESULT")

    def test_raw_response_secret_and_redirect_are_not_hidden(self):
        self.violation_and_repair('if (status < 200 || status >= 300 && status < 400)',
                                  'if (status < 200)', "SG_HTTP_CONSUMER_RESULT")
        self.violation_and_repair('\\\"message\\\":\\\"校准\\\"',
                                  '\\\"message\\\":\\\"consumer-calibration-secret\\\"', "SG_HTTP_CONSUMER_SECRET")

    def test_external_observer_is_not_executed(self):
        (self.root / "HttpConsumerInventory.java").write_text("untrusted observer must not compile")
        report = self.command()
        self.assertEqual(len(report["observed"]["wire"]), 16)
        self.assertIn("HttpConsumerInventory.java", report["configuration"])

    def test_failed_process_and_wrapped_encoded_secret_do_not_escape(self):
        self.violation_and_repair('return result;',
                                  'throw new IllegalStateException("consumer-calibration-secret");', "SG_HTTP_CONSUMER_SECRET")
        self.violation_and_repair('return result;',
            'System.out.println("Basic " + java.util.Base64.getEncoder().encodeToString("user:consumer-calibration-secret".getBytes())); return result;',
            "SG_HTTP_CONSUMER_SECRET")


if __name__ == "__main__":
    unittest.main()
