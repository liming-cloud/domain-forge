"""实际密码 Bean 的隔离正反例；不证明登录、数据库保存或生产工作参数。"""

import json
import os
import pathlib
import subprocess
import sys
import unittest

import test_http_contract


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_password.py"


class PasswordFixtures(test_http_contract.HttpContractFixtures):
    def setUp(self):
        super().setUp()
        self.password_tools = os.environ.get("DOMAIN_FORGE_GATE_PASSWORD_TOOLS", "")
        self.password = self.root / "password.json"
        self.declaration = {"schema": 1, "application": "probe.http.App", "bean": "passwordEncoder"}
        self.password_fixture()

    def password_fixture(self, implementation=None):
        self.fixture()
        pom = self.project / "pom.xml"
        dependencies = '<dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-crypto</artifactId></dependency>' \
                       '<dependency><groupId>org.bouncycastle</groupId><artifactId>bcprov-jdk18on</artifactId><version>1.86</version></dependency>'
        pom.write_text(pom.read_text(encoding="utf-8").replace('</dependencies>\n<build>', dependencies + '</dependencies>\n<build>'), encoding="utf-8")
        if implementation is None:
            implementation = 'return new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 19456, 2);'
        self.write("src/test-named-production/java/probe/http/PasswordConfiguration.java", '''package probe.http;
/** 实际密码 Bean 的隔离装配，不实现登录或账号初始化。 */
@org.springframework.context.annotation.Configuration
public class PasswordConfiguration {
  /** 提供被测实现，观察器不得替应用补出正确 Bean。 */
  @org.springframework.context.annotation.Bean
  public org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
    ''' + implementation + '''
  }
}
''')

    def wrapper(self, encode="return delegate.encode(raw);", matches="return delegate.matches(raw, encoded);"):
        return '''var delegate = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 19456, 2);
return new org.springframework.security.crypto.password.PasswordEncoder() {
  private final java.util.Map<String, String> values = new java.util.HashMap<>();
  public String encode(CharSequence raw) { ''' + encode + ''' }
  public boolean matches(CharSequence raw, String encoded) { ''' + matches + ''' }
};'''

    def command(self, diagnostic=None):
        self.password.write_text(json.dumps(self.declaration), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project),
                                 "--password", str(self.password), "--password-tools", self.password_tools,
                                 "--http-tools", self.http_tools, "--maven-home", self.maven, "--repository", self.repository],
                                capture_output=True, text=True, timeout=30)
        self.assertIn(result.returncode, (0, 1), result.stdout + result.stderr)
        self.assertTrue(result.stdout.strip(), result.stderr)
        report = json.loads(result.stdout)
        # 断言不复制合成密码或哈希；报告自身的敏感材料检查另有有效反例。
        detail = json.dumps({"ok": report.get("ok"), "diagnostics": report.get("diagnostics")}, ensure_ascii=False)
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, detail)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], detail)
        return report

    def repaired_password(self):
        self.repaired_project()
        self.password_fixture()


class PasswordBehaviorCommands(PasswordFixtures):
    def test_real_native_encoding_all_fixed_inputs_and_public_report(self):
        report = self.command()
        self.assertEqual(report["proof_scope"], "isolated-argon2id-bean-only")
        self.assertEqual({item["scenario"] for item in report["interactions"]}, {"ascii", "unicode", "long", "nul"})
        self.assertEqual(report["bean_type"], "org.springframework.security.crypto.argon2.Argon2PasswordEncoder")
        self.assertTrue(report["native_hashes_verified"])
        self.assertTrue(report["fresh_salts_verified"])
        for flag in ("product_verified", "load_parameters_verified", "login_verified", "human_acceptance"):
            self.assertFalse(report[flag])
        serialized = json.dumps(report, ensure_ascii=False)
        for value in ("Synthetic-Aa-Password!42", "合成-口令🙂-Z9", "$argon2id$", "Synthetic\\u0000password-A"):
            self.assertNotIn(value, serialized)

    def test_truncating_or_normalizing_input_cannot_pass(self):
        for transform in ('raw.toString().substring(0, Math.min(raw.length(), 72))', 'raw.toString().toLowerCase(java.util.Locale.ROOT)'):
            with self.subTest(transform=transform):
                self.repaired_password()
                self.password_fixture(self.wrapper('return delegate.encode(' + transform + ');',
                                                   'return delegate.matches(' + transform + ', encoded);'))
                self.command("SG_PASSWORD_MATCH")
        self.repaired_password()
        self.command()


class PasswordRejectionCommands(PasswordFixtures):
    def test_plaintext_or_fast_hash_cannot_self_certify(self):
        for encode, matches in (
                ('return raw.toString();', 'return encoded.equals(encode(raw));'),
                ('try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); }', 'return encoded.equals(encode(raw));'),
                ('return delegate.encode(raw).replace("$argon2id$", "$argon2i$");',
                 'return delegate.matches(raw, encoded.replace("$argon2i$", "$argon2id$"));')):
            with self.subTest(encoding=encode[:18]):
                self.repaired_password()
                self.password_fixture(self.wrapper(encode, matches))
                self.command("SG_PASSWORD_FORMAT")
        self.repaired_password()
        self.command()

    def test_reused_salt_or_forged_phc_cannot_self_certify(self):
        cached = 'return values.computeIfAbsent(raw.toString(), key -> delegate.encode(key));'
        forged = '''String[] parts = delegate.encode(raw).split("\\\\$");
byte[] bytes = java.util.Base64.getDecoder().decode(parts[5]); bytes[0] ^= 1;
parts[5] = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes);
String encoded = String.join("$", parts); values.put(encoded, raw.toString()); return encoded;'''
        for encode, matches, code in ((cached, 'return delegate.matches(raw, encoded);', "SG_PASSWORD_SALT"),
                                      (forged, 'return raw.toString().equals(values.get(encoded));', "SG_PASSWORD_NATIVE")):
            with self.subTest(diagnostic=code):
                self.repaired_password()
                self.password_fixture(self.wrapper(encode, matches))
                self.command(code)
        self.repaired_password()
        self.command()


class PasswordInputCommands(PasswordFixtures):
    def test_observed_password_or_encoded_hash_in_output_is_rejected(self):
        for output in ("System.out.println(raw);", "System.err.println(encoded);",
                       "System.err.println(java.util.Base64.getEncoder().encodeToString(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));"):
            with self.subTest(output=output[:28]):
                self.repaired_password()
                self.password_fixture(self.wrapper('String encoded = delegate.encode(raw); ' + output + ' return encoded;'))
                self.command("SG_PASSWORD_OUTPUT")
        self.repaired_password()
        self.command()

    def test_unpadded_encoded_hash_output_is_also_rejected(self):
        self.password_fixture(self.wrapper('''String encoded = delegate.encode(raw);
System.out.println(java.util.Base64.getEncoder().withoutPadding().encodeToString(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
return encoded;'''))
        self.command("SG_PASSWORD_OUTPUT")
        self.repaired_password()
        self.command()

    def test_definition_or_native_environment_cannot_be_omitted_then_repairs(self):
        original = dict(self.declaration)
        for change in ({"password": "not-accepted-input"}, {"bean": ""}):
            with self.subTest(fields=sorted(change)):
                self.declaration = dict(original, **change)
                self.command("SG_PASSWORD_INPUT")
                self.assertFalse((self.project / "target").exists())
        self.declaration = original
        tools = self.password_tools
        self.password_tools = str(self.root / "missing-tools")
        self.command("SG_PASSWORD_TOOLS")
        self.assertFalse((self.project / "target").exists())
        self.password_tools = tools
        self.command()


class PasswordToolQualityChecks(unittest.TestCase):
    def test_password_observer_keeps_owned_source_quality(self):
        tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(tools, "密码适配器质量必须提供真实源码工具")
        source = str(HERE / "PasswordInventory.java")
        for action in ("comments", "method-presence", "format", "complexity"):
            with self.subTest(action=action):
                argv = [sys.executable, "-B", str(HERE / "calibrate_source.py"), "--tools", tools, action]
                if action in ("comments", "complexity"):
                    argv.extend(["--purpose", "production"])
                result = subprocess.run([*argv, source], capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                self.assertEqual(report["scanned"], [source])
                if action == "complexity":
                    self.assertTrue(report["coverage"]["complete"])


class PasswordPartitionChecks(unittest.TestCase):
    def test_fixed_password_groups_cover_every_case_once_and_match_ci(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module, loader = sys.modules[__name__], unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "密码模块不得重复加载用例")
        selected = set()
        for check, names in (("password-behavior-tests", ["PasswordBehaviorCommands", "PasswordToolQualityChecks", "PasswordPartitionChecks"]),
                             ("password-rejection-tests", ["PasswordRejectionCommands"]),
                             ("password-input-tests", ["PasswordInputCommands"])):
            self.assertIn(check, catalog["checks"], "密码校准分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "tools/engineering_gate/test_password.py", *names],
                             "密码分组命令偏离登记")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"],
                             ["@python", "-B", "tools/engineering_gate/calibrate_password.py", "--environment"], "密码环境预检偏离固定入口")
            self.assertEqual(workflow.splitlines().count("        run: python -B tools/engineering_gate/test_password.py " + " ".join(names)), 1,
                             "密码 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group)
            self.assertEqual(len(group), len(set(group)), "密码分组内用例重复")
            self.assertFalse(selected & set(group), "密码跨组用例重复")
            selected.update(group)
        self.assertEqual(selected, set(complete), "密码分组遗漏用例")


if __name__ == "__main__":
    unittest.main()
