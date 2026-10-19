"""真实 PostgreSQL 启动迁移正反例；临时合成数据不能冒充产品持久化验收。"""

import hashlib
import json
import os
import pathlib
import secrets
import subprocess
import sys
import tempfile
import time
import types
import unittest
import uuid
import xml.etree.ElementTree as ET
from unittest.mock import patch

import test_maven_scope


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_flyway.py"


class FlywayFixtures(test_maven_scope.MavenScopeFixtures):
    def setUp(self):
        super().setUp()
        import calibrate_flyway

        self.gate = calibrate_flyway
        self.tools = os.environ.get("DOMAIN_FORGE_GATE_MIGRATION_TOOLS", "")
        self.assertTrue(self.tools, "必须准备原生依赖和真实 PostgreSQL，不跳过")
        self.endpoint = self.gate.local_endpoint()
        self.start_database()
        self.contract = self.root / "migration.json"
        self.fixture()

    def start_database(self):
        self.password = secrets.token_hex(24)
        environment = self.root / "database.env"
        environment.write_text("POSTGRES_DB=gate\nPOSTGRES_USER=gate\nPOSTGRES_PASSWORD=" + self.password + "\nPGPASSWORD=" + self.password + "\n", encoding="utf-8")
        environment.chmod(0o600)
        self.container = self.docker("run", "-d", "--rm", "--name", "domain-forge-flyway-" + uuid.uuid4().hex,
                                     "--label", "org.domainforge.calibration=flyway", "--tmpfs", "/var/lib/postgresql",
                                     "-p", "127.0.0.1::5432", "--env-file", str(environment), self.gate.baseline()["image"])
        self.assertRegex(self.container, r"^[0-9a-f]{64}$")
        self.addCleanup(self.release_database, self.container)
        for attempt in range(60):
            self.gate.check_docker_routing()
            ready = subprocess.run(["docker", "--host", self.endpoint, "exec", self.container, "psql", "-h", "127.0.0.1", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT 1;"],
                                   capture_output=True, text=True, timeout=5)
            if ready.returncode == 0 and ready.stdout.strip() == "1":
                break
            time.sleep(0.2)
        self.assertEqual(ready.returncode, 0, "新临时数据库未就绪，不算迁移反例")
        state = json.loads(self.docker("inspect", self.container))[0]
        port = state["NetworkSettings"]["Ports"]["5432/tcp"][0]["HostPort"]
        self.credentials = {"schema": 1, "container": self.container, "jdbc_url": "jdbc:postgresql://127.0.0.1:" + port + "/gate",
                            "username": "gate", "password": self.password}
        self.connection = self.root / "connection.json"
        self.connection.write_text(json.dumps(self.credentials), encoding="utf-8")
        self.connection.chmod(0o600)

    def docker(self, *arguments):
        self.gate.check_docker_routing()
        result = subprocess.run(["docker", "--host", self.endpoint, *arguments], capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, "隔离 Docker 命令失败，不当作产品违规：" + result.stderr)
        return result.stdout.strip()

    def release_database(self, container):
        inspected = json.loads(self.docker("inspect", container))[0]
        self.assertEqual(inspected["Config"]["Labels"].get("org.domainforge.calibration"), "flyway")
        self.docker("rm", "-f", container)

    def fixture(self, version="1", properties=""):
        versions = json.loads((HERE / "maven-scope.lock.json").read_text(encoding="utf-8"))
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                   '<groupId>probe</groupId><artifactId>flyway-calibration</artifactId><version>1.0.0</version>'
                   '<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>' +
                   self.gate.framework_pom() + '<build><sourceDirectory>src/test-named-production/java</sourceDirectory>'
                   '<testSourceDirectory>verification/java</testSourceDirectory><plugins>' + "".join('<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-' +
                   name + '-plugin</artifactId><version>' + versions[name + "_version"] + '</version></plugin>' for name in ("resources", "compiler")) + '</plugins></build></project>')
        self.write("src/test-named-production/java/probe/migration/App.java", '''package probe.migration;
/** 合成 SQL 启动迁移应用，不提供业务模块或管理员初始化。 */
@org.springframework.boot.autoconfigure.SpringBootApplication
public class App {}
''')
        self.write("src/main/resources/application.properties", '''spring.datasource.url=${DF_GATE_JDBC_URL}
spring.datasource.username=${DF_GATE_DB_USER}
spring.datasource.password=${DF_GATE_DB_PASSWORD}
spring.sql.init.mode=never
''' + properties)
        self.definition = {"schema": 1, "application": "probe.migration.App", "datasource": "dataSource", "flyway": "flyway",
                           "database_schema": "public", "target_version": version, "migrations": [],
                           "data": {"table": "gate_marker", "columns": ["id", "label"], "types": ["int4", "text"],
                                    "nullable": [False, False], "rows": [["1", "first"]]}}
        self.migration("1", "init", "CREATE TABLE gate_marker(id INTEGER PRIMARY KEY, label TEXT NOT NULL);\nINSERT INTO gate_marker VALUES ('1', 'first');")
        if version == "2":
            self.migration("2", "upgrade", "INSERT INTO gate_marker VALUES ('2', 'second');")
            self.definition["data"]["rows"].append(["2", "second"])

    def migration(self, version, name, sql):
        resource = "db/migration/V" + version + "__" + name + ".sql"
        path = "src/main/resources/" + resource
        written = self.write(path, sql)
        self.definition["migrations"].append({"version": version, "source": path, "resource": resource,
                                             "sha256": hashlib.sha256(written.read_bytes()).hexdigest()})

    def next_project(self, version="1", properties=""):
        self.repair_count += 1
        self.project = self.root / ("next-" + str(self.repair_count))
        self.fixture(version, properties)

    def command(self, diagnostic=None):
        self.contract.write_text(json.dumps(self.definition), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project), "--migration", str(self.contract),
                                 "--connection", str(self.connection), "--migration-tools", self.tools, "--maven-home", self.maven,
                                 "--repository", self.repository], capture_output=True, text=True, timeout=30)
        self.assertTrue(result.stdout.strip(), result.stderr)
        report = json.loads(result.stdout)
        detail = json.dumps({key: report.get(key) for key in ("ok", "proof_scope", "native_observation", "diagnostics")}, ensure_ascii=False)
        log = self.project / "target/flyway-observer-compile.log"
        if log.is_file():
            detail += "\n" + log.read_text(encoding="utf-8")[-2000:]
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, detail)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], detail)
        self.assertNotIn(self.password, json.dumps(report), "公开报告不能包含合成数据库凭证")
        return report


class MigrationBehaviorCommands(FlywayFixtures):
    def test_native_application_initializes_restarts_and_upgrades_without_tool_migrate(self):
        first = self.command()
        self.assertTrue(first["native_observation"]["started"])
        self.assertEqual(first["native_observation"]["migration_count"], 1)
        self.assertEqual(first["native_observation"]["row_count"], 1)
        self.docker("exec", self.container, "psql", "-U", "gate", "-d", "gate", "-v", "ON_ERROR_STOP=1", "-c", "INSERT INTO gate_marker VALUES (99, 'retained');")
        self.next_project()
        self.definition["data"]["rows"].append(["99", "retained"])
        self.assertEqual(self.command()["native_observation"]["row_count"], 2)
        self.next_project("2")
        self.definition["data"]["rows"].append(["99", "retained"])
        upgraded = self.command()
        self.assertEqual(upgraded["native_observation"]["migration_count"], 2)
        self.assertEqual(upgraded["native_observation"]["row_count"], 3)
        self.assertFalse(upgraded["product_verified"])
        self.assertFalse(upgraded["full_persistence_verified"])
        self.assertTrue(upgraded["startup_migrations_checked"])

    def test_migration_cannot_succeed_against_another_database(self):
        self.docker("exec", self.container, "psql", "-U", "gate", "-d", "gate", "-v", "ON_ERROR_STOP=1", "-c", "CREATE DATABASE other_gate;")
        other = self.credentials["jdbc_url"].removesuffix("/gate") + "/other_gate"
        self.fixture(properties="spring.flyway.url=" + other + "\nspring.flyway.user=${DF_GATE_DB_USER}\nspring.flyway.password=${DF_GATE_DB_PASSWORD}\n")
        report = self.command("SG_FLYWAY_DATABASE")
        self.assertTrue(report["native_observation"]["started"])
        self.assertEqual(self.docker("exec", self.container, "psql", "-U", "gate", "-d", "other_gate", "-At", "-c", "SELECT count(*) FROM gate_marker;"), "1")
        self.next_project()
        self.command()


class MigrationRejectionCommands(FlywayFixtures):
    def test_same_name_and_version_from_another_sql_location_cannot_pass(self):
        self.fixture(properties="spring.flyway.locations=classpath:wrong\n")
        source = (self.project / self.definition["migrations"][0]["source"]).read_text(encoding="utf-8")
        self.write("src/main/resources/wrong/V1__init.sql", source + "CREATE TABLE gate_shadow(id INTEGER);\n")
        try:
            self.command("SG_FLYWAY_RESOURCE")
        finally:
            self.assertEqual(self.docker("exec", self.container, "psql", "-U", "gate", "-d", "gate", "-At", "-c",
                                         "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='gate_shadow';"), "1")
        self.start_database()
        self.next_project()
        self.command()

    def test_wrong_scan_location_cannot_hide_declared_migration(self):
        self.fixture(properties="spring.flyway.locations=classpath:missing\n")
        self.command("SG_FLYWAY_COVERAGE")
        self.next_project()
        self.command()

    def test_matching_existing_data_cannot_hide_migration_to_another_schema(self):
        self.docker("exec", self.container, "psql", "-U", "gate", "-d", "gate", "-v", "ON_ERROR_STOP=1", "-c",
                    "CREATE TABLE gate_marker(id INTEGER PRIMARY KEY, label TEXT NOT NULL); INSERT INTO gate_marker VALUES (1, 'first');")
        self.fixture(properties="spring.flyway.schemas=wrong\n")
        try:
            self.command("SG_FLYWAY_SCHEMA")
        finally:
            self.assertEqual(self.docker("exec", self.container, "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT count(*) FROM wrong.gate_marker;"), "1")
        self.start_database()
        self.next_project()
        self.command()

    def test_actual_column_type_drift_cannot_pass_with_identical_row_strings(self):
        source = self.project / self.definition["migrations"][0]["source"]
        source.write_text(source.read_text(encoding="utf-8").replace("id INTEGER", "id TEXT"), encoding="utf-8")
        self.definition["migrations"][0]["sha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
        report = self.command("SG_FLYWAY_STRUCTURE")
        self.assertEqual(report["native_observation"]["columns"][0]["type"], "text")
        self.next_project("2")
        first = self.definition["migrations"][0]
        self.write(first["source"], source.read_text(encoding="utf-8").rstrip("\n"))
        first["sha256"] = hashlib.sha256((self.project / first["source"]).read_bytes()).hexdigest()
        second = self.definition["migrations"][1]
        changed = self.write(second["source"], "ALTER TABLE gate_marker ALTER COLUMN id TYPE INTEGER USING id::integer;\nINSERT INTO gate_marker VALUES (2, 'second');")
        second["sha256"] = hashlib.sha256(changed.read_bytes()).hexdigest()
        self.command()

    def test_disabled_startup_migration_cannot_pass_an_empty_database(self):
        self.fixture(properties="spring.flyway.enabled=false\n")
        self.command("SG_FLYWAY_COVERAGE")
        self.next_project()
        self.command()


class MigrationHistoryCommands(FlywayFixtures):
    def test_applied_sql_rewrite_is_rejected_by_native_validation_and_new_upgrade_repairs(self):
        self.command()
        self.next_project()
        item = self.definition["migrations"][0]
        path = self.project / item["source"]
        path.write_text(path.read_text(encoding="utf-8").replace("'first'", "'changed'"), encoding="utf-8")
        item["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        report = self.command("SG_FLYWAY_STARTUP")
        self.assertFalse(report["native_observation"]["started"])
        self.assertIn("VALIDATE_ERROR", [item.get("flyway-code") for item in report["native_observation"]["failures"]])
        self.next_project("2")
        self.command()

    def test_disabling_validate_on_migrate_cannot_hide_history_drift(self):
        self.command()
        self.next_project(properties="spring.flyway.validate-on-migrate=false\n")
        item = self.definition["migrations"][0]
        path = self.project / item["source"]
        path.write_text(path.read_text(encoding="utf-8").replace("'first'", "'changed'"), encoding="utf-8")
        item["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        report = self.command("SG_FLYWAY_VALIDATE")
        self.assertTrue(report["native_observation"]["started"])
        self.next_project("2")
        self.command()

    def test_failed_sql_does_not_erase_previously_committed_migration(self):
        self.fixture("2")
        item = self.definition["migrations"][1]
        path = self.write(item["source"], "THIS IS NOT SQL;")
        item["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        report = self.command("SG_FLYWAY_STARTUP")
        self.assertIn("42601", [item.get("sqlstate") for item in report["native_observation"]["failures"]])
        self.assertEqual(self.docker("exec", self.container, "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT version FROM flyway_schema_history WHERE success;"), "1")
        self.next_project("2")
        self.command()


class MigrationIntegrityCommands(FlywayFixtures):
    def test_valid_history_and_same_row_count_cannot_hide_data_drift(self):
        self.command()
        self.docker("exec", self.container, "psql", "-U", "gate", "-d", "gate", "-v", "ON_ERROR_STOP=1", "-c", "UPDATE gate_marker SET label='wrong';")
        self.next_project()
        report = self.command("SG_FLYWAY_DATA")
        self.assertEqual(report["native_observation"]["row_count"], 1)
        self.next_project("2")
        item = self.definition["migrations"][1]
        path = self.write(item["source"], "UPDATE gate_marker SET label='first' WHERE id=1;\nINSERT INTO gate_marker VALUES (2, 'second');")
        item["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.command()

    def test_synthetic_credential_output_and_compiled_sql_drift_are_not_public_proof(self):
        app = self.project / "src/test-named-production/java/probe/migration/App.java"
        app.write_text(app.read_text(encoding="utf-8").replace("public class App {}", '''public class App {
  /** 故意输出本轮合成凭证，用于真实泄露反例。 */
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner leak() {
    return args -> System.out.println(System.getenv("DF_GATE_DB_PASSWORD"));
  }
}'''), encoding="utf-8")
        self.command("SG_FLYWAY_SECRET_OUTPUT")
        self.next_project()
        app = self.project / "src/test-named-production/java/probe/migration/App.java"
        app.write_text(app.read_text(encoding="utf-8").replace("public class App {}", '''public class App {
  /** 故意只改变编译资源，维护源码保持不变。 */
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner drift() {
    return args -> {
      var path = java.nio.file.Path.of(App.class.getResource("/db/migration/V1__init.sql").toURI());
      java.nio.file.Files.writeString(path, java.nio.file.Files.readString(path) + "\\n");
    };
  }
}'''), encoding="utf-8")
        self.command("SG_INPUT_CHANGED")
        self.next_project()
        self.command()


class MigrationInputCommands(FlywayFixtures):
    def test_contract_cannot_be_empty_duplicated_or_invalid(self):
        original = json.loads(json.dumps(self.definition))
        for change in ("empty", "duplicate", "target", "traversal", "identifier", "nullability"):
            with self.subTest(change=change):
                self.definition = json.loads(json.dumps(original))
                if change == "empty":
                    self.definition["migrations"] = []
                elif change == "duplicate":
                    self.definition["migrations"] *= 2
                elif change == "target":
                    self.definition["target_version"] = "9"
                elif change == "traversal":
                    self.definition["migrations"][0]["source"] = "../outside.sql"
                elif change == "identifier":
                    self.definition["data"]["table"] = "marker;DROP TABLE marker"
                else:
                    self.definition["data"]["nullable"] = [0, 0]
                self.command("SG_FLYWAY_INPUT")
                self.assertFalse((self.project / "target").exists())
        self.definition = original
        self.command()

    def test_private_connection_and_owned_target_are_required_before_application_execution(self):
        original = json.loads(json.dumps(self.credentials))
        for change in ("permissions", "schema", "remote", "unknown"):
            with self.subTest(change=change):
                connection = json.loads(json.dumps(original))
                if change == "permissions":
                    self.connection.chmod(0o644)
                elif change == "schema":
                    connection["schema"] = True
                elif change == "remote":
                    connection["jdbc_url"] = "jdbc:postgresql://example.invalid:5432/gate"
                else:
                    connection["container"] = "0" * 64
                self.connection.write_text(json.dumps(connection), encoding="utf-8")
                self.command("SG_FLYWAY_CONNECTION")
                self.assertFalse((self.project / "target").exists())
                self.connection.chmod(0o600)
        self.connection.write_text(json.dumps(original), encoding="utf-8")
        self.command()

    def test_independent_sql_digest_and_missing_tools_cannot_pass(self):
        self.definition["migrations"][0]["sha256"] = "0" * 64
        self.command("SG_FLYWAY_RESOURCE")
        self.next_project()
        original, self.tools = self.tools, ""
        self.command("SG_FLYWAY_TOOLS")
        self.assertFalse((self.project / "target").exists())
        self.tools = original
        self.command()


class MigrationToolQualityChecks(unittest.TestCase):
    def test_native_observer_keeps_owned_source_quality(self):
        tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(tools, "原生源码质量工具不能缺失或跳过")
        for action in ("comments", "method-presence", "format", "complexity"):
            command = [sys.executable, "-B", str(HERE / "calibrate_source.py"), "--tools", tools, action]
            if action in ("comments", "complexity"):
                command.extend(["--purpose", "production"])
            result = subprocess.run([*command, str(HERE / "FlywayInventory.java")], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class MigrationBoundaryChecks(unittest.TestCase):
    def test_docker_routing_overrides_fail_before_any_command(self):
        import calibrate_flyway
        from calibrate_source import ProbeFailure

        for key, value in (("DOCKER_HOST", "tcp://example.invalid:2375"), ("DOCKER_CONTEXT", "remote-context")):
            with self.subTest(key=key), patch.dict(os.environ, {key: value}), patch.object(calibrate_flyway, "execute") as execute:
                execute.return_value = types.SimpleNamespace(returncode=0, stdout="29.2.1")
                with self.assertRaises(ProbeFailure) as error:
                    calibrate_flyway.docker("version", "--format", "{{.Server.Version}}")
                self.assertEqual(error.exception.diagnostic["code"], "SG_FLYWAY_ENVIRONMENT")
                execute.assert_not_called()

    def test_mount_metadata_cannot_hide_host_or_existing_volume_data(self):
        import calibrate_flyway
        from calibrate_source import ProbeFailure

        credentials = {"schema": 1, "container": "a" * 64, "jdbc_url": "jdbc:postgresql://127.0.0.1:55432/gate",
                       "username": "gate", "password": "synthetic-only-long-password"}
        original = {"Id": credentials["container"], "State": {"Running": True},
                    "Config": {"Labels": {"org.domainforge.calibration": "flyway"}, "Image": calibrate_flyway.baseline()["image"]},
                    "HostConfig": {"AutoRemove": True, "Binds": [], "Tmpfs": {"/var/lib/postgresql": ""}, "Mounts": []},
                    "Mounts": [{"Type": "tmpfs", "Destination": "/var/lib/postgresql"}],
                    "NetworkSettings": {"Ports": {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "55432"}]}}}
        with tempfile.TemporaryDirectory(prefix="domain-forge-flyway-boundary-") as directory:
            private = pathlib.Path(directory) / "connection.json"
            private.write_text(json.dumps(credentials), encoding="utf-8")
            private.chmod(0o600)
            for mounts in ([], original["Mounts"]):
                inspected = dict(original, Mounts=mounts)

                def observed(command):
                    content = "unix:///private/tmp/synthetic-docker.sock" if "context" in command else json.dumps([inspected])
                    return types.SimpleNamespace(returncode=0, stdout=content)

                with patch.dict(os.environ, {"DOCKER_HOST": "", "DOCKER_CONTEXT": ""}), patch.object(calibrate_flyway, "execute", side_effect=observed):
                    self.assertEqual(calibrate_flyway.connection(private), credentials)
            for field, kind in (("host-config", "bind"), ("actual", "bind"), ("actual", "volume")):
                with self.subTest(field=field, kind=kind):
                    inspected = json.loads(json.dumps(original))
                    if field == "host-config":
                        inspected["HostConfig"]["Mounts"].append({"Type": kind, "Source": "/external-data", "Target": "/extra"})
                    else:
                        inspected["Mounts"].append({"Type": kind, "Source": "/external-data", "Destination": "/extra"})

                    def observed(command):
                        content = "unix:///private/tmp/synthetic-docker.sock" if "context" in command else json.dumps([inspected])
                        return types.SimpleNamespace(returncode=0, stdout=content)

                    with patch.dict(os.environ, {"DOCKER_HOST": "", "DOCKER_CONTEXT": ""}), patch.object(calibrate_flyway, "execute", side_effect=observed):
                        with self.assertRaises(ProbeFailure) as error:
                            calibrate_flyway.connection(private)
                        self.assertEqual(error.exception.diagnostic["code"], "SG_FLYWAY_CONNECTION")


class MigrationObservationChecks(unittest.TestCase):
    def test_observation_requires_native_resources_explicit_values_and_sql_identity(self):
        import calibrate_flyway
        from calibrate_source import ProbeFailure

        with tempfile.TemporaryDirectory(prefix="domain-forge-flyway-observation-") as directory:
            output = pathlib.Path(directory)
            resource = output / "db/migration/V1__init.sql"
            resource.parent.mkdir(parents=True)
            resource.write_text("SELECT 1;", encoding="utf-8")
            source = {"target_version": "1", "database_schema": "public", "migrations": [{"version": "1", "resource": "db/migration/V1__init.sql", "sha256": hashlib.sha256(resource.read_bytes()).hexdigest()}],
                      "data": {"columns": ["id"], "types": ["int4"], "nullable": [False], "rows": [["1"]]}}
            credentials = {"jdbc_url": "jdbc:postgresql://127.0.0.1:55432/gate"}
            document = ET.Element("flyway-observation", started="true", initializers="1")
            database = ET.SubElement(document, "database", {"jdbc-url": credentials["jdbc_url"], "product": "PostgreSQL", "version": "18.4", "driver": "42.7.13"})
            ET.SubElement(database, "column", name="id", type="int4", nullable="0")
            ET.SubElement(ET.SubElement(database, "row"), "value", null="false").text = "1"
            flyway = ET.SubElement(document, "flyway", {"jdbc-url": credentials["jdbc_url"], "valid": "true", "current": "1", "schema": "public", "managed-schemas": ""})
            migration = ET.SubElement(flyway, "migration", version="1", script="V1__init.sql", type="SQL", state="SUCCESS", physical=str(resource))
            original = ET.tostring(document)
            self.assertEqual(calibrate_flyway.assess(document, source, credentials, output), [])
            for change, diagnostic in (("startup", "SG_FLYWAY_OBSERVATION"), ("null", "SG_FLYWAY_OBSERVATION"),
                                       ("database", "SG_FLYWAY_OBSERVATION"), ("duplicate", "SG_FLYWAY_COVERAGE"),
                                       ("physical", "SG_FLYWAY_RESOURCE"), ("schema", "SG_FLYWAY_SCHEMA")):
                with self.subTest(change=change):
                    document = ET.fromstring(original)
                    if change == "startup":
                        del document.attrib["started"]
                    elif change == "null":
                        del document.find("database/row/value").attrib["null"]
                    elif change == "database":
                        document.append(ET.fromstring(ET.tostring(database)))
                    elif change == "duplicate":
                        document.find("flyway").append(ET.fromstring(ET.tostring(migration)))
                    elif change == "physical":
                        del document.find("flyway/migration").attrib["physical"]
                    else:
                        del document.find("flyway").attrib["schema"]
                    if diagnostic == "SG_FLYWAY_OBSERVATION":
                        with self.assertRaises(ProbeFailure) as error:
                            calibrate_flyway.assess(document, source, credentials, output)
                        self.assertEqual(error.exception.diagnostic["code"], diagnostic)
                    else:
                        self.assertIn(diagnostic, [item["code"] for item in calibrate_flyway.assess(document, source, credentials, output)])


class MigrationPartitionChecks(unittest.TestCase):
    def test_fixed_migration_groups_cover_every_case_once_and_match_ci(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module, loader = sys.modules[__name__], unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "迁移模块不得重复加载用例")
        selected = set()
        for check, names in (("migration-behavior-tests", ["MigrationBehaviorCommands", "MigrationToolQualityChecks", "MigrationPartitionChecks"]),
                             ("migration-rejection-tests", ["MigrationRejectionCommands"]),
                             ("migration-history-tests", ["MigrationHistoryCommands"]),
                             ("migration-integrity-tests", ["MigrationIntegrityCommands"]),
                             ("migration-input-tests", ["MigrationInputCommands", "MigrationBoundaryChecks", "MigrationObservationChecks"])):
            self.assertIn(check, catalog["checks"], "迁移校准分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "harness/engineering_gate/test_flyway.py", *names], "迁移分组命令偏离登记")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            self.assertEqual(catalog["checks"][check]["environment_argv"], ["@python", "-B", "harness/engineering_gate/calibrate_flyway.py", "--environment"], "迁移环境预检偏离固定入口")
            self.assertEqual(workflow.splitlines().count("        run: python -B harness/engineering_gate/test_flyway.py " + " ".join(names)), 1, "迁移 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group)
            self.assertEqual(len(group), len(set(group)), "迁移分组内用例重复")
            self.assertFalse(selected & set(group), "迁移跨组用例重复")
            selected.update(group)
        self.assertEqual(selected, set(complete), "迁移分组遗漏用例")


if __name__ == "__main__":
    unittest.main()
