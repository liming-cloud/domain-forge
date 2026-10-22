"""当前真实工程的构建、打包和覆盖反例；使用声明的工具环境，缺失即失败。"""

import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

import build
import environment
import calibrate_lifecycle
import calibrate_maven
import calibrate_source
import advisory

ROOT = Path(__file__).resolve().parents[2]


class NativeProductTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        environment.runtime(ROOT)
        cls.repository = Path(os.environ["DOMAIN_FORGE_PRODUCT_REPOSITORY"]).resolve()
        cls.cache = Path(os.environ["DOMAIN_FORGE_PRODUCT_WRAPPER_CACHE"]).resolve()
        cls.tools = os.environ["DOMAIN_FORGE_GATE_TOOLS"]
        cls.project, cls.output, cls.args = cls.fixture()
        cls.observed = build.lifecycle(ROOT, cls.project, *cls.args, cls.repository, cls.output)

    @classmethod
    def fixture(cls):
        workspace = Path(tempfile.mkdtemp(prefix="domain-forge-native-product-test-")).resolve()
        project = environment.copy_project(ROOT, workspace / "project", environment.sources(ROOT))
        output = workspace / "evidence"
        output.mkdir()
        argv, env, home, _ = environment.bootstrap(ROOT, project, cls.cache, cls.repository, output)
        return project, output, (argv, env, home)

    def test_real_product_source_architecture_and_required_tests_pass(self):
        result = build.quality(ROOT, self.project, self.observed, self.tools)
        self.assertTrue(result["ok"], result["diagnostics"])
        self.assertGreater(self.observed["tests"]["count"], 0)
        self.assertEqual(self.observed["reactor"], [
            ".", "framework", "framework/domain-forge-domain", "framework/domain-forge-web-support",
            "framework/domain-forge-persistence", "domain-forge-bom", "verification",
            "verification/smoke", "verification/smoke/boot-smoke", "framework/access-control",
            "framework/access-control/domain-forge-access-control-contract",
            "framework/access-control/domain-forge-access-control-backend", "framework/domain-forge-iam-client", "services", "services/iam",
        ])
        self.assertEqual({item["purpose"] for item in result["bindings"]}, {"production", "test"})

    def test_observed_reactor_cannot_omit_or_add_modules(self):
        document = ET.parse(self.output / "maven-inventory.xml").getroot()
        for mutation in ("omitted", "extra"):
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(document)
                if mutation == "omitted":
                    changed.remove(changed.findall("project")[0])
                else:
                    changed.append(copy.deepcopy(changed.find("project")))
                with self.assertRaises(environment.ProbeFailure) as error:
                    build.module_inventory(changed, self.project)
                self.assertEqual(error.exception.diagnostic["code"], "PG_BUILD_SCOPE")

    def test_native_skipped_tests_are_rejected_despite_successful_packaging(self):
        project, output, args = self.fixture()
        pom = environment.product_root(project) / "pom.xml"
        pom.write_text(pom.read_text().replace("<failIfNoTests>true</failIfNoTests>",
                                             "<failIfNoTests>true</failIfNoTests><skipTests>true</skipTests>"))
        with self.assertRaises(environment.ProbeFailure) as error:
            build.lifecycle(ROOT, project, *args, self.repository, output)
        self.assertEqual(error.exception.diagnostic["code"], "SG_BUILD_TESTS")
        self.assertIn("Tests are skipped", (output / "verify.log").read_text())

    def test_module_build_uses_repository_parent_without_install(self):
        project, output, args = self.fixture()
        argv, env, home = args
        self.assertFalse((self.repository / "org/domainforge/platform").exists())
        extension = calibrate_maven.observer(home, output)
        inventory = output / "direct-module.xml"
        environment.command([*argv, "-f", project / "pom.xml", "-Dmaven.ext.class.path=" + str(extension),
                             "-Ddomainforge.scope.output=" + str(inventory), "verify"],
                            project, output / "direct-module.log", 600, env)
        document = ET.parse(inventory).getroot()
        self.assertEqual([x.get("path") for x in document.findall("project")], [str(project)])
        compilations = calibrate_maven.compilation_inventory(document, project,
                                                            calibrate_maven.project_inputs(project), self.repository)
        tests = calibrate_lifecycle.test_results(project, compilations, set(environment.baseline(ROOT)["required_tests"]))
        self.assertGreater(tests["count"], 0)
        artifact = Path(document.find("project").get("artifactFile"))
        with zipfile.ZipFile(artifact) as archive:
            self.assertIn("Main-Class: org.springframework.boot.loader.launch.JarLauncher",
                          archive.read("META-INF/MANIFEST.MF").decode())
        self.assertFalse((self.repository / "org/domainforge/platform").exists())

    def test_missing_explicit_boot_repackage_is_rejected(self):
        project, output, args = self.fixture()
        pom = project / "pom.xml"
        document = ET.parse(pom)
        plugin = document.find("./{*}build/{*}plugins/{*}plugin")
        plugin.remove(plugin.find("{*}executions"))
        document.write(pom)
        with self.assertRaises(environment.ProbeFailure) as error:
            build.lifecycle(ROOT, project, *args, self.repository, output)
        self.assertEqual(error.exception.diagnostic["code"], "PG_BUILD_ARTIFACT")

    def test_zero_tests_and_real_failed_assertion_are_not_green(self):
        for mutation in ("zero", "assertion"):
            with self.subTest(mutation=mutation):
                project, output, args = self.fixture()
                test = project / "src/test/java/org/domainforge/boot/ApplicationHealthTest.java"
                if mutation == "zero":
                    test.unlink()
                else:
                    test.write_text(test.read_text().replace("assertEquals(200,", "assertEquals(201,"))
                with self.assertRaises(environment.ProbeFailure) as error:
                    build.lifecycle(ROOT, project, *args, self.repository, output)
                self.assertEqual(error.exception.diagnostic["code"], "PG_NATIVE_FAILURE")
                text = (output / "verify.log").read_text()
                self.assertIn("No tests" if mutation == "zero" else "expected: <201>", text)

    def test_unowned_actual_production_type_is_detected(self):
        project, output, args = self.fixture()
        source = project / "src/main/java/unowned/Hidden.java"
        source.parent.mkdir()
        source.write_text("package unowned;\n\n/** 验证未归属的真实生产类型必须被发现。 */\npublic class Hidden {}\n")
        observed = build.lifecycle(ROOT, project, *args, self.repository, output)
        result = build.quality(ROOT, project, observed, self.tools)
        self.assertIn("SG_UNKNOWN_OWNER", {item["code"] for item in result["diagnostics"]})
        self.assertTrue(any(item["name"] == "unowned.Hidden" and item["purpose"] == "production" for item in result["bindings"]))

    def test_missing_packaged_class_and_resource_are_rejected(self):
        original = Path(self.observed["artifact"]["path"])
        document = ET.parse(self.output / "module-inventory.xml").getroot()
        for missing in (self.observed["artifact"]["classes"][0], self.observed["artifact"]["resources"][0]):
            with self.subTest(missing=missing):
                changed = self.project / "target/tampered.jar"
                with zipfile.ZipFile(original) as source, zipfile.ZipFile(changed, "w") as target:
                    for item in source.infolist():
                        if item.filename != missing:
                            target.writestr(item, source.read(item.filename))
                observed_document = copy.deepcopy(document)
                observed_document.find("project").set("artifactFile", str(changed))
                with self.assertRaises(environment.ProbeFailure) as error:
                    build.packaged(observed_document, self.project, self.observed["build"], self.observed["dependencies"])
                self.assertEqual(error.exception.diagnostic["code"], "PG_BUILD_ARTIFACT")

    def test_required_test_omission_and_floating_dependency_are_rejected(self):
        with self.assertRaises(environment.ProbeFailure) as error:
            calibrate_lifecycle.test_results(self.project, self.observed["build"]["compilations"], {"Missing#scenario"})
        self.assertEqual(error.exception.diagnostic["code"], "SG_BUILD_TEST_COVERAGE")
        document = ET.parse(self.output / "module-inventory.xml").getroot()
        document.find("project/declaredDependency").set("version", "[4.0,5.0)")
        with self.assertRaises(environment.ProbeFailure) as error:
            calibrate_lifecycle.versions_and_artifacts(document, self.repository)
        self.assertEqual(error.exception.diagnostic["code"], "SG_BUILD_VERSION")

    def test_actual_source_format_comments_complexity_and_undeclared_api_fail(self):
        project, output, args = self.fixture()
        source = project / "src/main/java/org/domainforge/boot/Untidy.java"
        source.write_text("package org.domainforge.boot;\nclass Untidy{int count(){int n=0;\n" +
                          "n++;\n" * 31 + "return n + org.springframework.core.SpringVersion.getVersion().length();}}\n")
        pom = project / "pom.xml"
        document = ET.parse(pom)
        dependencies = document.find("./{*}dependencies")
        for dependency in list(dependencies):
            if dependency.findtext("{*}artifactId") == "spring-core":
                dependencies.remove(dependency)
        document.write(pom)
        observed = build.lifecycle(ROOT, project, *args, self.repository, output)
        quality = build.quality(ROOT, project, observed, self.tools)
        diagnostics = quality["diagnostics"]
        self.assertFalse(quality["ok"])
        self.assertIn("SG_FORMAT", {item["code"] for item in diagnostics})
        self.assertIn("ExecutableStatementCount", {item.get("rule") for item in diagnostics})
        self.assertIn("MissingJavadocType", {item.get("rule") for item in diagnostics})
        self.assertTrue(any("spring-core" in json.dumps(item) for item in diagnostics), diagnostics)

    def test_actual_native_scanner_rejects_invalid_database(self):
        workspace = Path(tempfile.mkdtemp(prefix="domain-forge-product-advisory-error-"))
        data = workspace / "invalid-data"
        data.mkdir()
        (data / "odc.mv.db").write_bytes(b"invalid native database")
        with self.assertRaises(environment.ProbeFailure) as error:
            advisory.scan(ROOT, workspace, environment.sources(ROOT), self.repository, data,
                          self.args[0], self.args[1], workspace, self.observed)
        self.assertEqual(error.exception.diagnostic["code"], "PG_NATIVE_FAILURE")
        self.assertIn("ERROR", (workspace / "advisory.log").read_text())


if __name__ == "__main__":
    unittest.main()
