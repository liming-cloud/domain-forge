"""核对基础 Maven 扫描接入；配置测试不冒充真实漏洞扫描。"""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


CONFIG = Path(__file__).with_name("maven-dependency-check.xml")


class MavenAdvisoryConfiguration(unittest.TestCase):
    def plugin(self):
        self.assertTrue(CONFIG.is_file(), "缺少基础 Maven 安全告警配置")
        return ET.fromstring(CONFIG.read_bytes())

    def test_fixed_mature_plugin_is_bound_to_verification(self):
        plugin = self.plugin()
        self.assertEqual(plugin.tag, "plugin")
        self.assertEqual(plugin.findtext("groupId"), "org.owasp")
        self.assertEqual(plugin.findtext("artifactId"), "dependency-check-maven")
        self.assertEqual(plugin.findtext("version"), "13.0.0")
        self.assertEqual(plugin.findtext("executions/execution/phase"), "verify")
        self.assertEqual(plugin.findtext("executions/execution/goals/goal"), "check")

    def test_findings_are_advisory_but_analysis_errors_are_not_success(self):
        config = self.plugin().find("configuration")
        self.assertIsNotNone(config)
        self.assertEqual(config.findtext("failBuildOnCVSS"), "11")
        self.assertEqual(config.findtext("failOnError"), "true")
        self.assertEqual({node.text for node in config.findall("formats/format")},
                         {"HTML", "JSON"})

    def test_resolved_dependencies_include_test_scope_without_service_account(self):
        config = self.plugin().find("configuration")
        self.assertIsNotNone(config)
        self.assertEqual(config.findtext("scanDependencies"), "true")
        self.assertEqual(config.findtext("skipTestScope"), "false")
        self.assertEqual(config.findtext("ossIndexAnalyzerEnabled"), "false")
        self.assertEqual(config.findtext("nvdDatafeedUrl"),
                         "https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz")
        self.assertIsNone(config.find("suppressionFiles"))
        self.assertIsNone(config.find("autoUpdate"))
        self.assertIsNone(config.find("skip"))


if __name__ == "__main__":
    unittest.main()
