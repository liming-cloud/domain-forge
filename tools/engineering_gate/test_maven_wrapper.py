"""官方脚本在隔离目录真实下载并启动 Maven；不把启动当作产品构建。"""

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

from test_maven_scope import MavenScopeFixtures


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_wrapper.py"


class MavenWrapperCommands(MavenScopeFixtures):
    def setUp(self):
        super().setUp()
        self.archive = os.environ.get("DOMAIN_FORGE_GATE_WRAPPER", "")
        self.assertTrue(self.archive, "必须提供官方固定 Wrapper 制品，不能跳过")
        self.assertFalse(any(os.environ.get(name) for name in ("MVNW_REPOURL", "MVNW_USERNAME", "MVNW_PASSWORD", "MVNW_VERBOSE")),
                         "原生反例不接受隐式地址、认证或调试覆盖，不能静默改写环境")
        self.versions = json.loads((HERE / "maven-scope.lock.json").read_text(encoding="utf-8"))
        self.assertEqual(hashlib.sha256(pathlib.Path(self.archive).read_bytes()).hexdigest(), self.versions["wrapper"]["sha256"])
        with zipfile.ZipFile(self.archive) as archive:
            for name in ("mvnw", "mvnw.cmd"):
                (self.project / name).write_bytes(archive.read(name))
        (self.project / "mvnw").chmod(0o755)
        self.properties = self.write(".mvn/wrapper/maven-wrapper.properties", "\n".join([
            "wrapperVersion=" + self.versions["wrapper"]["version"], "distributionType=only-script",
            "distributionUrl=" + self.versions["url"],
            "distributionSha256Sum=" + self.versions["distribution_sha256"]]))

    def wrapper_command(self, diagnostic=None, **overrides):
        values = dict(project=str(self.project), maven_home=self.maven, repository=self.repository, wrapper=self.archive)
        values.update(overrides)
        argv = [sys.executable, "-B", str(ENTRY)]
        for key, value in values.items():
            argv.extend(["--" + key.replace("_", "-"), value])
        result = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
        report = json.loads(result.stdout)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], report)
        return report

    def test_official_wrapper_bootstraps_pinned_maven_without_claiming_build(self):
        inputs = {str(path): path.read_bytes() for path in self.project.rglob("*") if path.is_file()}
        report = self.wrapper_command()
        self.assertEqual(report["proof_scope"], "isolated-wrapper-bootstrap-only")
        self.assertTrue(report["distribution_bootstrapped"])
        for field in ("build_proven", "publication_proven", "human_acceptance"):
            self.assertFalse(report[field])
        self.assertEqual(report["command"][0], str(self.project / "mvnw"))
        self.assertEqual(report["command"][-1], "--version")
        self.assertIn("Apache Maven " + self.versions["version"], report["stdout"])
        self.assertEqual(report["distribution_core_sha256"], self.versions["core_sha256"])
        self.assertEqual(inputs, {str(path): path.read_bytes() for path in self.project.rglob("*") if path.is_file()})
        self.assertFalse((self.project / "target").exists())

    def test_checksum_failure_is_native_wrapper_rejection_then_repair_bootstraps(self):
        original = self.properties.read_text(encoding="utf-8")
        self.properties.write_text(original.replace(self.versions["distribution_sha256"], "0" * 64), encoding="utf-8")
        with tempfile.TemporaryDirectory(prefix="domain-forge-wrapper-negative-cache-") as cache:
            environment = dict(os.environ, MAVEN_USER_HOME=cache, MAVEN_SKIP_RC="true")
            result = subprocess.run([str(self.project / "mvnw"), "--version"], cwd=self.project,
                                    env=environment, capture_output=True, text=True, timeout=30)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Failed to validate Maven distribution SHA-256", result.stderr)
        self.wrapper_command("SG_WRAPPER_INPUT")
        self.properties.write_text(original, encoding="utf-8")
        self.wrapper_command()

    def test_modified_script_and_implicit_maven_configuration_are_rejected(self):
        script = self.project / "mvnw"
        original = script.read_bytes()
        script.write_bytes(original + b"\n# modified\n")
        self.wrapper_command("SG_WRAPPER_INPUT")
        script.write_bytes(original)
        configuration = self.write(".mvn/maven.config", "-Dmaven.test.skip=true")
        self.wrapper_command("SG_WRAPPER_INPUT")
        configuration.unlink()
        self.wrapper_command()

    def test_missing_archive_invalid_properties_and_symlink_do_not_run(self):
        self.wrapper_command("SG_WRAPPER_ARTIFACT", wrapper=str(self.root / "missing.zip"))
        original = self.properties.read_text(encoding="utf-8")
        for change in (original.replace("wrapperVersion=3.3.4", "wrapperVersion=unverified"),
                       original + "\ndistributionSha256Sum=" + "0" * 64,
                       original.replace(self.versions["url"], "https://example.invalid/maven.zip")):
            self.properties.write_text(change, encoding="utf-8")
            self.wrapper_command("SG_WRAPPER_INPUT")
        self.properties.write_text(original, encoding="utf-8")
        alias = self.project / "alias.java"
        alias.symlink_to(self.project / "pom.xml")
        self.wrapper_command("SG_BUILD_INPUT")
        alias.unlink()
        self.wrapper_command()

    def test_preparation_preserves_existing_user_files(self):
        before = (self.project / "pom.xml").read_bytes()
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--prepare", str(self.project)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)["diagnostics"][0]["code"], "SG_WRAPPER_ARTIFACT")
        self.assertEqual((self.project / "pom.xml").read_bytes(), before)
        self.assertFalse((self.project / "wrapper.zip").exists())


if __name__ == "__main__":
    unittest.main()
