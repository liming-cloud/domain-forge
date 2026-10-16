"""真实产品入口的定向拒绝合同；模拟错误不作为产品通过证据。"""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import environment
import build
import runtime
import advisory

ROOT = Path(__file__).resolve().parents[2]


class ProductInputTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="domain-forge-product-input-")
        self.addCleanup(temporary.cleanup)
        self.project = environment.copy_project(ROOT, Path(temporary.name) / "project", environment.sources(ROOT))
        self.root = self.project.parents[2]
        self.code = self.project.parents[1]
        shutil.copytree(ROOT / "tools/product_gate", self.root / "tools/product_gate")

    def reject(self, code, function, *args):
        with self.assertRaises(environment.ProbeFailure) as caught:
            function(*args)
        self.assertEqual(code, caught.exception.diagnostic["code"])

    def test_unregistered_module_is_not_silently_excluded(self):
        target = self.root / "code/components/unregistered/pom.xml"
        target.parent.mkdir(parents=True)
        target.write_text("<project/>\n")
        self.reject("PG_BUILD_SCOPE", environment.sources, self.root)

    def test_empty_extra_or_implicit_aggregate_cannot_pass_preflight(self):
        pom = self.code / "pom.xml"
        original = pom.read_text()
        for altered in (original.replace("<module>verification/boot-smoke</module>", ""),
                        original.replace("</modules>", "<module>components/hidden</module></modules>"),
                        original.replace("</project>", "<profiles/></project>")):
            with self.subTest(altered=altered):
                pom.write_text(altered)
                self.reject("PG_BUILD_SCOPE", build.model, ROOT, self.project)
        pom.write_text(original)

    def test_legacy_root_and_linked_code_partition_are_rejected(self):
        legacy = self.root / "pom.xml"
        legacy.write_text("<project/>")
        self.reject("PG_BUILD_SCOPE", environment.sources, self.root)
        legacy.unlink()
        source = self.code / "verification/alias"
        source.symlink_to(self.project, target_is_directory=True)
        self.reject("PG_INPUT", environment.sources, self.root)

    def test_wrapper_script_and_checksum_drift_are_rejected(self):
        script = self.code / "mvnw"
        script.write_text(script.read_text() + "\n# drift\n")
        self.reject("PG_WRAPPER", environment.wrapper, self.root)
        shutil.copy2(ROOT / "code/mvnw", script)
        config = self.code / ".mvn/wrapper/maven-wrapper.properties"
        config.write_text(config.read_text().replace("distributionSha256Sum=", "distributionSha256Sum=00"))
        self.reject("PG_WRAPPER", environment.wrapper, self.root)

    def test_git_preserves_official_wrapper_bytes_even_with_input_autocrlf(self):
        for name in ("code/mvnw", "code/mvnw.cmd"):
            raw = subprocess.check_output(["git", "hash-object", "--no-filters", name], cwd=ROOT)
            filtered = subprocess.check_output(["git", "-c", "core.autocrlf=input", "hash-object",
                                                "--path=" + name, name], cwd=ROOT)
            self.assertEqual(raw, filtered, "Git 不得改写官方脚本原始字节")

    def test_untracked_source_and_resource_are_included_and_symlink_rejected(self):
        source = self.project / "src/main/java/Unexpected.java"
        source.write_text("class Unexpected {}\n")
        resource = self.project / "src/main/resources/new.txt"
        resource.write_text("must be packaged\n")
        actual = environment.sources(self.root)
        self.assertIn("code/verification/boot-smoke/src/main/java/Unexpected.java", actual)
        self.assertIn("code/verification/boot-smoke/src/main/resources/new.txt", actual)
        source.unlink()
        source.symlink_to(ROOT / "code/verification/boot-smoke/src/main/java/org/domainforge/boot/DomainForgeApplication.java")
        self.reject("PG_INPUT", environment.sources, self.root)

    def test_unknown_jvm_options_and_mismatched_java_home_are_rejected(self):
        with patch.dict(os.environ, MAVEN_OPTS="-DskipTests=true"):
            self.reject("PG_ENVIRONMENT", environment.runtime, ROOT)
        with patch.dict(os.environ, JAVA_HOME="/missing"):
            self.reject("PG_ENVIRONMENT", environment.runtime, ROOT)
        with patch.dict(os.environ, SPRING_APPLICATION_JSON='{"server":{"address":"0.0.0.0"}}'):
            self.reject("PG_ENVIRONMENT", environment.runtime, ROOT)

    def test_non_loopback_and_implicit_maven_configuration_are_rejected(self):
        self.reject("PG_RUNTIME_TARGET", runtime.loopback, "0.0.0.0")
        self.reject("PG_RUNTIME_TARGET", runtime.loopback, "::1")
        (self.code / ".mvn/maven.config").write_text("-DskipTests\n")
        self.reject("PG_INPUT", environment.wrapper, self.root)

    def test_changed_boot_or_non_loopback_configuration_cannot_pass_preflight(self):
        pom = self.project / "pom.xml"
        original = pom.read_text()
        pom.write_text(original.replace("<version>4.0.8</version>", "<version>4.0.9-SNAPSHOT</version>"))
        self.reject("PG_BASELINE", build.model, ROOT, self.project)
        pom.write_text(original)
        config = self.project / "src/main/resources/application.properties"
        config.write_text(config.read_text().replace("server.address=127.0.0.1", "server.address=0.0.0.0"))
        self.reject("PG_RUNTIME_TARGET", build.model, ROOT, self.project)

    def test_timeout_and_failed_assertion_have_distinct_results(self):
        log = self.project.parent / "timeout.log"
        self.reject("PG_PREPARATION", environment.command,
                    [sys.executable, "-c", "import time; time.sleep(5)"], self.project, log, 0.05, None, True)
        self.reject("PG_NATIVE_FAILURE", environment.command,
                    [sys.executable, "-c", "assert False, 'target assertion'"], self.project, log)

    def test_merged_native_dependencies_keep_exact_production_and_test_coverage(self):
        report = {"dependencies": [{"sha256": "production", "vulnerabilities": [{"name": "known-warning"}],
                                    "relatedDependencies": [{"sha256": "test"}]}]}
        observed = {"dependencies": [{"sha256": name, "extension": "jar"} for name in ("production", "test")]}
        self.assertEqual(advisory.coverage(report, observed), {"production", "test"})
        report["dependencies"][0]["relatedDependencies"] = []
        self.reject("PG_ADVISORY_COVERAGE", advisory.coverage, report, observed)
        self.assertEqual(report["dependencies"][0]["vulnerabilities"], [{"name": "known-warning"}])

    def test_missing_advisory_data_and_native_tool_error_never_become_zero_risk(self):
        self.reject("PG_ADVISORY_DATA", advisory.scan, ROOT, self.project.parent, {}, None,
                    None, [], None, self.project.parent, {})
        data = self.project.parent / "data"
        data.mkdir()
        (data / "odc.mv.db").write_bytes(b"invalid-native-database")
        with patch.object(advisory, "command", side_effect=environment.ProbeFailure("PG_NATIVE_FAILURE", "native scan failed")):
            self.reject("PG_NATIVE_FAILURE", advisory.scan, ROOT, self.project.parent,
                        environment.sources(ROOT), None, data, ["mvn"], None, self.project.parent, {})


if __name__ == "__main__":
    unittest.main()
