"""实际 MyBatis-Plus、动态路由与本地事务校准；不实现产品业务。"""

import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

import test_flyway


HERE = pathlib.Path(__file__).resolve().parent
ENTRY = HERE / "calibrate_persistence.py"


class PersistenceFixtures(test_flyway.FlywayFixtures):
    def setUp(self):
        super().setUp()
        import calibrate_persistence

        self.persistence_gate = calibrate_persistence
        self.persistence_tools = os.environ.get("DOMAIN_FORGE_GATE_PERSISTENCE_TOOLS", "")
        self.assertTrue(self.persistence_tools, "必须准备真实持久化依赖，不跳过")
        self.private = self.root / "connections.json"
        self.intent = self.root / "persistence.json"
        self.databases()
        self.persistence_fixture()

    def databases(self):
        self.peers = {}
        self.peers["master"] = dict(self.credentials)
        self.start_database()
        self.peers["inventory"] = dict(self.credentials)
        self.private.write_text(json.dumps({"schema": 1, "databases": self.peers}), encoding="utf-8")
        self.private.chmod(0o600)
        for alias, credentials in self.peers.items():
            self.docker("exec", credentials["container"], "psql", "-U", "gate", "-d", "gate", "-v", "ON_ERROR_STOP=1", "-c",
                        "CREATE TABLE gate_record(id BIGINT PRIMARY KEY, label TEXT NOT NULL); INSERT INTO gate_record VALUES (100, '" + alias + "');")

    def persistence_fixture(self, secondary="inventory", strict="true", adapter_extra=""):
        pom = self.project / "pom.xml"
        original = pom.read_text(encoding="utf-8")
        start, end = original.index("<dependencyManagement>"), original.index("<build>")
        pom.write_text(original[:start] + self.persistence_gate.framework_pom() + original[end:], encoding="utf-8")
        properties = "spring.flyway.enabled=false\nmybatis-plus.global-config.banner=false\nspring.datasource.dynamic.primary=master\nspring.datasource.dynamic.strict=" + strict + "\n"
        for alias in self.peers:
            for key, suffix in (("url", "URL"), ("username", "USER"), ("password", "PASSWORD")):
                properties += "spring.datasource.dynamic.datasource." + alias + "." + key + "=${DF_GATE_" + alias.upper() + "_" + suffix + "}\n"
        self.write("src/main/resources/application.properties", properties)
        self.write("src/test-named-production/java/probe/persistence/App.java", '''package probe.persistence;
/** 原生数据源与 Mapper 隔离装配，不提供业务功能。 */
@org.springframework.boot.autoconfigure.SpringBootApplication
@org.mybatis.spring.annotation.MapperScan("probe.persistence.infrastructure")
public class App {}
''')
        self.write("src/test-named-production/java/probe/persistence/TransactionConfiguration.java", '''package probe.persistence;
/** 以 Spring 原生配置统一事务顺序和默认异常回滚，不进入业务层。 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
@org.springframework.transaction.annotation.EnableTransactionManagement(order=100, rollbackOn=org.springframework.transaction.annotation.RollbackOn.ALL_EXCEPTIONS)
public class TransactionConfiguration {}
''')
        self.write("src/test-named-production/java/probe/persistence/infrastructure/GateRow.java", '''package probe.persistence.infrastructure;
/** 只存在于校准临时库的合成记录。 */
@com.baomidou.mybatisplus.annotation.TableName("gate_record")
public class GateRow {
  @com.baomidou.mybatisplus.annotation.TableId(type=com.baomidou.mybatisplus.annotation.IdType.INPUT)
  private Long id;
  private String label;
  public GateRow() {}
  public GateRow(Long id, String label) { this.id=id; this.label=label; }
  public Long getId() { return id; }
  public void setId(Long id) { this.id=id; }
  public String getLabel() { return label; }
  public void setLabel(String label) { this.label=label; }
}
''')
        self.write("src/test-named-production/java/probe/persistence/infrastructure/GateMapper.java", '''package probe.persistence.infrastructure;
/** 使用实际 MyBatis-Plus 通用映射，不创建应用层通用 Service。 */
public interface GateMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<GateRow> {}
''')
        self.write("src/test-named-production/java/probe/persistence/infrastructure/PrimaryAdapter.java", '''package probe.persistence.infrastructure;
/** 无路由声明时使用真实默认数据源。 */
@org.springframework.stereotype.Service
public class PrimaryAdapter {
  private final GateMapper mapper;
  public PrimaryAdapter(GateMapper mapper) { this.mapper=mapper; }
  public String read() { return mapper.selectById(100L).getLabel(); }
}
''')
        self.write("src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java", '''package probe.persistence.infrastructure;
/** 以基础设施上的原生注解选择已声明资源，事务由真实代理执行。 */
@org.springframework.stereotype.Service
@com.baomidou.dynamic.datasource.annotation.DS("''' + secondary + '''")
public class InventoryAdapter {
  private final GateMapper mapper;
  public InventoryAdapter(GateMapper mapper) { this.mapper=mapper; }
  public String read() { return mapper.selectById(100L).getLabel(); }
  @org.springframework.transaction.annotation.Transactional
  public int commit() { return mapper.insert(new GateRow(1L, "committed")); }
  @org.springframework.transaction.annotation.Transactional
  public int rollback() { mapper.insert(new GateRow(2L, "rolled-back")); throw new IllegalStateException("synthetic rollback"); }
  ''' + adapter_extra + '''
}
''')
        self.write("src/test-named-production/java/probe/persistence/infrastructure/UnknownAdapter.java", '''package probe.persistence.infrastructure;
/** 未登记的别名必须由真实路由执行拒绝，不能回落到主库。 */
@org.springframework.stereotype.Service
@com.baomidou.dynamic.datasource.annotation.DS("missing")
public class UnknownAdapter {
  private final GateMapper mapper;
  public UnknownAdapter(GateMapper mapper) { this.mapper=mapper; }
  public String read() { return mapper.selectById(100L).getLabel(); }
}
''')
        initial = {"master": [["100", "master"]], "inventory": [["100", "inventory"]]}
        committed = dict(initial, inventory=[*initial["inventory"], ["1", "committed"]])
        operations = []
        for scenario, bean, method, lookup, database, transaction, result, exception, rows in (
                ("primary", "primaryAdapter", "read", "", "master", False, "master", None, initial),
                ("secondary", "inventoryAdapter", "read", "inventory", "inventory", False, "inventory", None, initial),
                ("commit", "inventoryAdapter", "commit", "inventory", "inventory", True, "1", None, committed),
                ("rollback", "inventoryAdapter", "rollback", "inventory", "inventory", True, None, "java.lang.IllegalStateException", committed),
                ("unknown", "unknownAdapter", "read", "missing", None, False, None, "com.baomidou.dynamic.datasource.exception.CannotFindDataSourceException", committed),
                ("restored-primary", "primaryAdapter", "read", "", "master", False, "master", None, committed)):
            operations.append({"scenario": scenario, "bean": bean, "method": method, "lookup": lookup, "database": database, "transaction": transaction,
                               "result": result, "exception": exception, "statement": "probe.persistence.infrastructure.GateMapper." + ("insert" if transaction else "selectById"), "rows": rows})
        self.definition = {"schema": 1, "application": "probe.persistence.App", "datasource": "dataSource", "factory": "sqlSessionFactory", "manager": "transactionManager",
                           "data": {"schema": "public", "table": "gate_record", "columns": ["id", "label"]}, "operations": operations}

    def command(self, diagnostic=None):
        self.intent.write_text(json.dumps(self.definition), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--project", str(self.project), "--persistence", str(self.intent), "--connections", str(self.private),
                                 "--persistence-tools", self.persistence_tools, "--migration-tools", self.tools, "--maven-home", self.maven, "--repository", self.repository],
                                capture_output=True, text=True, timeout=30)
        self.assertTrue(result.stdout.strip(), result.stderr)
        report = json.loads(result.stdout)
        detail = json.dumps({key: report.get(key) for key in ("ok", "proof_scope", "native_observation", "diagnostics")}, ensure_ascii=False)
        log = self.project / "target/persistence-observer-compile.log"
        if log.is_file():
            detail += "\n" + log.read_text(encoding="utf-8")[-2000:]
        self.assertEqual(result.returncode, 0 if diagnostic is None else 1, detail)
        if diagnostic:
            self.assertIn(diagnostic, [item["code"] for item in report["diagnostics"]], detail)
        for credentials in self.peers.values():
            self.assertNotIn(credentials["password"], json.dumps(report))
        return report

    def repaired_persistence(self):
        self.repair_count += 1
        self.project = self.root / ("persistence-next-" + str(self.repair_count))
        self.fixture()
        self.start_database()
        self.databases()
        self.persistence_fixture()

    def application_transactions(self):
        self.write("src/test-named-production/java/probe/persistence/application/InventoryRepository.java", '''package probe.persistence.application;
/** 技术无关的合成仓储协议，不包含数据库别名。 */
public interface InventoryRepository { String read(); int commit(); int rollback(); }
''')
        self.write("src/test-named-production/java/probe/persistence/application/InventoryUseCase.java", '''package probe.persistence.application;
/** 应用声明业务事务范围，不直接选择数据源。 */
@org.springframework.stereotype.Service
public class InventoryUseCase {
  private final InventoryRepository inventory;
  public InventoryUseCase(InventoryRepository inventory) { this.inventory=inventory; }
  @org.springframework.transaction.annotation.Transactional
  public String read() { return inventory.read(); }
  @org.springframework.transaction.annotation.Transactional
  public int commit() { return inventory.commit(); }
  @org.springframework.transaction.annotation.Transactional
  public int rollback() { return inventory.rollback(); }
}
''')
        adapter = self.project / "src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java"
        source = adapter.read_text(encoding="utf-8").replace("public class InventoryAdapter {", "public class InventoryAdapter implements probe.persistence.application.InventoryRepository {")
        adapter.write_text(source.replace("@org.springframework.transaction.annotation.Transactional", ""), encoding="utf-8")
        for operation in self.definition["operations"][1:4]:
            operation.update(bean="inventoryUseCase", transaction=True)

    def resource_binding(self, **replacements):
        source = (HERE / "ResourceRoutingFixture.java").read_text(encoding="utf-8")
        for old, new in replacements.items():
            source = source.replace(old, new)
        self.write("src/test-named-production/java/probe/persistence/assembly/ResourceRoutingFixture.java", source)

    def checked_rollback(self):
        self.application_transactions()
        adapter = self.project / "src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java"
        adapter.write_text(adapter.read_text(encoding="utf-8").replace('mapper.insert(new GateRow(2L, "rolled-back")); throw new IllegalStateException("synthetic rollback");',
                           'return mapper.insert(new GateRow(2L, "rolled-back"));'), encoding="utf-8")
        application = self.project / "src/test-named-production/java/probe/persistence/application/InventoryUseCase.java"
        application.write_text(application.read_text(encoding="utf-8").replace('public int rollback() { return inventory.rollback(); }',
                               'public int rollback() throws java.io.IOException { inventory.rollback(); throw new java.io.IOException("synthetic checked failure"); }'), encoding="utf-8")
        self.definition["operations"][3]["exception"] = "java.io.IOException"


class PersistenceBehaviorCommands(PersistenceFixtures):
    def test_real_mapper_routes_commits_rolls_back_and_restores_default(self):
        report = self.command()
        self.assertTrue(report["native_observation"]["started"])
        self.assertEqual(len(report["native_observation"]["operations"]), 6)
        self.assertFalse(report["full_persistence_verified"])
        self.assertFalse(report["product_verified"])

    def test_method_alias_can_override_class_alias_without_extra_wrapper(self):
        self.persistence_fixture(secondary="master")
        path = self.project / "src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java"
        source = path.read_text(encoding="utf-8")
        for method in ("public String read()", "public int commit()", "public int rollback()"):
            source = source.replace(method, '@com.baomidou.dynamic.datasource.annotation.DS("inventory")\n  ' + method)
        path.write_text(source, encoding="utf-8")
        self.command()


class PersistenceRoutingCommands(PersistenceFixtures):
    def test_unknown_assembly_binding_cannot_fall_back_even_with_native_strict_disabled(self):
        self.persistence_fixture(strict="false")
        self.application_transactions()
        self.resource_binding(**{'"inventory"': '"missing"'})
        report = self.command("SG_PERSISTENCE_EXECUTION")
        observed = report["native_observation"]["operations"][1]
        self.assertEqual(observed["exception"], "java.lang.IllegalStateException")
        self.assertEqual(observed["trace_count"], 0)
        self.repaired_persistence()
        self.application_transactions()
        self.resource_binding()
        self.command()

    def test_wrong_alias_cannot_self_certify_with_correct_annotations_present(self):
        self.persistence_fixture(secondary="master")
        try:
            self.command("SG_PERSISTENCE_ROUTE")
        finally:
            self.assertEqual(self.docker("exec", self.peers["master"]["container"], "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT count(*) FROM gate_record WHERE id=1;"), "1")
            self.assertEqual(self.docker("exec", self.peers["inventory"]["container"], "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT count(*) FROM gate_record WHERE id=1;"), "0")
        self.repaired_persistence()
        self.command()

    def test_unknown_alias_cannot_fall_back_to_default_when_strict_is_disabled(self):
        self.persistence_fixture(strict="false")
        report = self.command("SG_PERSISTENCE_ROUTE")
        self.assertIsNone(next(item for item in report["native_observation"]["operations"] if item["scenario"] == "unknown")["exception"])
        self.repaired_persistence()
        self.command()


class PersistenceTransactionCommands(PersistenceFixtures):
    def test_runtime_only_scenario_cannot_hide_missing_global_rollback_default(self):
        configuration = self.project / "src/test-named-production/java/probe/persistence/TransactionConfiguration.java"
        configuration.write_text(configuration.read_text(encoding="utf-8").replace("RollbackOn.ALL_EXCEPTIONS", "RollbackOn.RUNTIME_EXCEPTIONS"), encoding="utf-8")
        report = self.command("SG_PERSISTENCE_DEFAULT_ROLLBACK")
        self.assertNotIn("SG_PERSISTENCE_DATA", [item["code"] for item in report["diagnostics"]], "只测运行时异常时数据结果仍正确，必须独立识别默认配置缺口")
        self.repaired_persistence()
        self.command()

    def assert_rollback_was_really_committed(self):
        self.assertEqual(self.docker("exec", self.peers["inventory"]["container"], "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT count(*) FROM gate_record WHERE id=2;"), "1")

    def test_removed_transaction_annotation_cannot_pass_with_successful_commit(self):
        path = self.project / "src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java"
        path.write_text(path.read_text(encoding="utf-8").replace("@org.springframework.transaction.annotation.Transactional", ""), encoding="utf-8")
        self.command("SG_PERSISTENCE_TRANSACTION")
        self.assert_rollback_was_really_committed()
        self.repaired_persistence()
        self.command()

    def test_transaction_manager_bound_to_different_resource_cannot_pass(self):
        self.write("src/test-named-production/java/probe/persistence/infrastructure/WrongTransaction.java", '''package probe.persistence.infrastructure;
/** 故意把事务绑定到物理主库，Mapper 仍使用动态源，验证真实资源错配。 */
@org.springframework.context.annotation.Configuration
public class WrongTransaction {
  @org.springframework.context.annotation.Bean
  public org.springframework.jdbc.datasource.DataSourceTransactionManager transactionManager(com.baomidou.dynamic.datasource.DynamicRoutingDataSource source) {
    return new org.springframework.jdbc.datasource.DataSourceTransactionManager(source.getDataSource("master"));
  }
}
''')
        self.command("SG_PERSISTENCE_TRANSACTION")
        self.assert_rollback_was_really_committed()
        self.repaired_persistence()
        self.command()


class PersistenceExecutionCommands(PersistenceFixtures):
    def test_self_invocation_cannot_replace_actual_transaction_proxy(self):
        path = self.project / "src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java"
        source = path.read_text(encoding="utf-8")
        source = source.replace('@org.springframework.transaction.annotation.Transactional\n  public int rollback()',
                                'public int rollback() { return rollbackInternal(); }\n  @org.springframework.transaction.annotation.Transactional\n  public int rollbackInternal()')
        path.write_text(source, encoding="utf-8")
        self.command("SG_PERSISTENCE_TRANSACTION")
        self.assertEqual(self.docker("exec", self.peers["inventory"]["container"], "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT count(*) FROM gate_record WHERE id=2;"), "1")
        self.repaired_persistence()
        self.command()

    def test_handwritten_result_cannot_replace_an_actual_mapper_execution(self):
        path = self.project / "src/test-named-production/java/probe/persistence/infrastructure/PrimaryAdapter.java"
        path.write_text(path.read_text(encoding="utf-8").replace('mapper.selectById(100L).getLabel()', '"master"'), encoding="utf-8")
        self.command("SG_PERSISTENCE_EXECUTION")
        self.repaired_persistence()
        self.command()


class PersistenceContextCommands(PersistenceFixtures):
    def test_nested_application_transaction_on_same_resource_is_allowed(self):
        self.application_transactions()
        self.write("src/test-named-production/java/probe/persistence/application/InventoryFlow.java", '''package probe.persistence.application;
/** 同资源合成用例组合，验证合法事务参与不被路由保护误拒绝。 */
@org.springframework.stereotype.Service
public class InventoryFlow {
  private final InventoryUseCase inventory;
  public InventoryFlow(InventoryUseCase inventory) { this.inventory=inventory; }
  @org.springframework.transaction.annotation.Transactional
  public String read() { return inventory.read(); }
}
''')
        self.resource_binding(**{'InventoryUseCase.class.getMethod("read"), "inventory",':
                                'probe.persistence.application.InventoryFlow.class.getMethod("read"), "inventory", InventoryUseCase.class.getMethod("read"), "inventory",'})
        self.definition["operations"][1].update(bean="inventoryFlow")
        self.command()

    def test_application_transaction_cannot_choose_secondary_repository_after_resource_is_bound(self):
        self.write("src/test-named-production/java/probe/persistence/application/InventoryRepository.java", '''package probe.persistence.application;
/** 技术无关的合成库存读取协议，不包含数据源类型或别名。 */
public interface InventoryRepository { String read(); }
''')
        self.write("src/test-named-production/java/probe/persistence/application/InventoryUseCase.java", '''package probe.persistence.application;
/** 应用明确事务边界，基础设施独自声明数据源，验证两者真实装配结果。 */
@org.springframework.stereotype.Service
public class InventoryUseCase {
  private final InventoryRepository inventory;
  public InventoryUseCase(InventoryRepository inventory) { this.inventory=inventory; }
  @org.springframework.transaction.annotation.Transactional
  public String read() { return inventory.read(); }
}
''')
        adapter = self.project / "src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java"
        adapter.write_text(adapter.read_text(encoding="utf-8").replace("public class InventoryAdapter {", "public class InventoryAdapter implements probe.persistence.application.InventoryRepository {"), encoding="utf-8")
        self.definition["operations"][1].update(bean="inventoryUseCase", transaction=True)
        report = self.command("SG_PERSISTENCE_ROUTE")
        observed = report["native_observation"]["operations"][1]["executions"]
        self.assertEqual(observed, [{"database": "master", "transaction": True, "prepared": True, "completed": True}], "需证明应用事务先绑定主库，不把其他失败当作架构冲突")
        self.repaired_persistence()
        self.command()


    def test_manual_lookup_leak_cannot_pollute_later_default_reads(self):
        path = self.project / "src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java"
        path.write_text(path.read_text(encoding="utf-8").replace('return mapper.selectById(100L).getLabel();',
                        'String value = mapper.selectById(100L).getLabel(); com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder.push("master"); return value;'), encoding="utf-8")
        self.command("SG_PERSISTENCE_CONTEXT")
        self.repaired_persistence()
        self.command()


class PersistenceBindingCommands(PersistenceFixtures):
    def test_assembly_routes_before_application_transactions_without_application_ds(self):
        self.application_transactions()
        self.resource_binding()
        report = self.command()
        for operation in report["native_observation"]["operations"][1:4]:
            self.assertEqual(operation["executions"], [{"database": "inventory", "transaction": True, "prepared": True, "completed": True}])

    def test_missing_method_binding_cannot_pass_then_same_application_is_repaired(self):
        self.application_transactions()
        self.resource_binding(**{'InventoryUseCase.class.getMethod("read"), "inventory",': ''})
        report = self.command("SG_PERSISTENCE_ROUTE")
        self.assertEqual(report["native_observation"]["operations"][1]["executions"][0]["database"], "master")
        self.repaired_persistence()
        self.application_transactions()
        self.resource_binding()
        self.command()

    def test_routing_after_transaction_is_rejected_and_repair_restores_native_results(self):
        self.application_transactions()
        self.resource_binding(**{'advisor.setOrder(0);': 'advisor.setOrder(200);'})
        report = self.command("SG_PERSISTENCE_EXECUTION")
        observed = report["native_observation"]["operations"][1]
        self.assertEqual(observed["exception"], "java.lang.IllegalStateException")
        self.assertEqual(observed["trace_count"], 0, "错误顺序由应用装配拒绝，不由观察器补救")
        self.repaired_persistence()
        self.application_transactions()
        self.resource_binding()
        self.command()


class PersistenceRollbackCommands(PersistenceFixtures):
    def test_checked_exception_uses_global_native_rollback_default(self):
        self.checked_rollback()
        self.resource_binding()
        self.command()

    def test_missing_global_rollback_default_really_commits_then_repairs(self):
        self.checked_rollback()
        self.resource_binding()
        configuration = self.project / "src/test-named-production/java/probe/persistence/TransactionConfiguration.java"
        configuration.write_text(configuration.read_text(encoding="utf-8").replace("RollbackOn.ALL_EXCEPTIONS", "RollbackOn.RUNTIME_EXCEPTIONS"), encoding="utf-8")
        report = self.command("SG_PERSISTENCE_DATA")
        self.assertEqual(report["native_observation"]["operations"][3]["exception"], "java.io.IOException")
        self.assertEqual(self.docker("exec", self.peers["inventory"]["container"], "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT count(*) FROM gate_record WHERE id=2;"), "1")
        self.repaired_persistence()
        self.checked_rollback()
        self.resource_binding()
        self.command()

    def explicit_commit_fixture(self):
        self.checked_rollback()
        self.resource_binding(**{'InventoryUseCase.class.getMethod("read"), "inventory",':
                                'InventoryUseCase.class.getMethod("specialCommit"), "inventory", InventoryUseCase.class.getMethod("read"), "inventory",'})
        repository = self.project / "src/test-named-production/java/probe/persistence/application/InventoryRepository.java"
        repository.write_text(repository.read_text(encoding="utf-8").replace("int rollback();", "int rollback(); int specialCommit();"), encoding="utf-8")
        adapter = self.project / "src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java"
        adapter.write_text(adapter.read_text(encoding="utf-8").replace("public int commit()", 'public int specialCommit() { return mapper.insert(new GateRow(3L, "explicit-commit")); }\n  public int commit()'), encoding="utf-8")
        application = self.project / "src/test-named-production/java/probe/persistence/application/InventoryUseCase.java"
        application.write_text(application.read_text(encoding="utf-8").replace("  private final InventoryRepository inventory;", '''
  @org.springframework.transaction.annotation.Transactional(noRollbackFor=java.io.IOException.class)
  public int specialCommit() throws java.io.IOException { inventory.specialCommit(); throw new java.io.IOException("declared commit exception"); }
  private final InventoryRepository inventory;'''), encoding="utf-8")
        self.write("src/test-named-production/java/probe/persistence/SpecialCommitWitness.java", '''package probe.persistence;
/** 在原生代理上执行已明确声明的异常提交例外，不把全部异常机械判为回滚。 */
@org.springframework.context.annotation.Configuration
public class SpecialCommitWitness {
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner verifyExplicitException(probe.persistence.application.InventoryUseCase useCase) {
    return args -> { try { useCase.specialCommit(); throw new AssertionError("checked exception missing"); } catch (java.io.IOException expected) {} };
  }
}
''')
        states = {id(operation["rows"]): operation["rows"] for operation in self.definition["operations"]}
        for rows in states.values():
            rows["inventory"].append(["3", "explicit-commit"])

    def test_explicit_commit_exception_is_allowed_with_actual_declared_result(self):
        self.explicit_commit_fixture()
        self.command()
        self.assertEqual(self.docker("exec", self.peers["inventory"]["container"], "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT count(*) FROM gate_record WHERE id=3;"), "1")

    def test_missing_explicit_exception_cannot_pass_an_intended_commit(self):
        self.explicit_commit_fixture()
        application = self.project / "src/test-named-production/java/probe/persistence/application/InventoryUseCase.java"
        application.write_text(application.read_text(encoding="utf-8").replace("noRollbackFor=java.io.IOException.class", ""), encoding="utf-8")
        self.command("SG_PERSISTENCE_DATA")
        self.assertEqual(self.docker("exec", self.peers["inventory"]["container"], "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT count(*) FROM gate_record WHERE id=3;"), "0")
        self.repaired_persistence()
        self.explicit_commit_fixture()
        self.command()
        self.assertEqual(self.docker("exec", self.peers["inventory"]["container"], "psql", "-U", "gate", "-d", "gate", "-At", "-c", "SELECT count(*) FROM gate_record WHERE id=3;"), "1")


class PersistencePolicyCommands(PersistenceFixtures):
    def test_native_pointcut_and_advice_must_share_the_observed_metadata_source(self):
        self.write("src/test-named-production/java/probe/persistence/MismatchedPointcut.java", '''package probe.persistence;
/** 两个行为相同的原生来源不是同一装配对象，限定校准不能推定其长期一致。 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
public class MismatchedPointcut {
  @org.springframework.context.annotation.Bean
  public static org.springframework.beans.factory.config.BeanFactoryPostProcessor differentPointcutSource() {
    return factory -> {
      var source = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
      source.addDefaultRollbackRule(org.springframework.transaction.interceptor.RollbackRuleAttribute.ROLLBACK_ON_ALL_EXCEPTIONS);
      factory.getBeanDefinition("org.springframework.transaction.config.internalTransactionAdvisor").getPropertyValues().add("transactionAttributeSource", source);
    };
  }
}
''')
        report = self.command("SG_PERSISTENCE_CALIBRATION")
        self.assertTrue(report["native_observation"]["started"])
        self.assertNotIn("SG_PERSISTENCE_DATA", [item["code"] for item in report["diagnostics"]])
        self.repaired_persistence()
        self.command()

    def test_idle_global_advisor_cannot_certify_the_actual_runtime_only_advisor(self):
        self.write("src/test-named-production/java/probe/persistence/IdleAdvisor.java", '''package probe.persistence;
/** 故意使全异常 Advisor 闲置，实际操作改由运行时异常事务执行。 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
public class IdleAdvisor {
  @org.springframework.context.annotation.Bean
  public static org.springframework.beans.factory.config.BeanFactoryPostProcessor excludeOriginal() {
    return factory -> factory.getBeanDefinition("org.springframework.transaction.config.internalTransactionAdvisor").getPropertyValues()
      .add("classFilter", (org.springframework.aop.ClassFilter) type -> false);
  }
  @org.springframework.context.annotation.Bean
  public org.springframework.aop.Advisor actualTransactions(org.springframework.beans.factory.BeanFactory factory) {
    var source = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
    var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
    interceptor.setTransactionAttributeSource(source);
    interceptor.setBeanFactory(factory);
    var advisor = new org.springframework.transaction.interceptor.TransactionAttributeSourceAdvisor(interceptor);
    advisor.setOrder(100);
    return advisor;
  }
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner verifyIdleAdvisor(org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor idle,
      probe.persistence.infrastructure.InventoryAdapter inventory) {
    return args -> {
      if (java.util.List.of(((org.springframework.aop.framework.Advised)inventory).getAdvisors()).contains(idle)) throw new AssertionError("global advisor is not idle");
      var source = ((org.springframework.transaction.interceptor.TransactionInterceptor)idle.getAdvice()).getTransactionAttributeSource();
      var metadata = source.getTransactionAttribute(probe.persistence.infrastructure.InventoryAdapter.class.getMethod("rollback"), probe.persistence.infrastructure.InventoryAdapter.class);
      if (!metadata.rollbackOn(new java.io.IOException())) throw new AssertionError("idle source does not declare global rollback");
    };
  }
}
''')
        report = self.command("SG_PERSISTENCE_DEFAULT_ROLLBACK")
        self.assertNotIn("SG_PERSISTENCE_DATA", [item["code"] for item in report["diagnostics"]])
        self.repaired_persistence()
        self.command()

class PersistencePolicySourceCommands(PersistenceFixtures):
    def test_custom_native_source_subclass_is_a_calibration_gap_not_a_pass(self):
        self.write("src/test-named-production/java/probe/persistence/CustomSource.java", '''package probe.persistence;
/** 原生类型的未校准子类不能冒充已校准来源。 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
public class CustomSource {
  public static class Extension extends org.springframework.transaction.annotation.AnnotationTransactionAttributeSource {
    public Extension() { addDefaultRollbackRule(org.springframework.transaction.interceptor.RollbackRuleAttribute.ROLLBACK_ON_ALL_EXCEPTIONS); }
  }
  @org.springframework.context.annotation.Bean
  public static org.springframework.beans.factory.config.BeanFactoryPostProcessor replaceSource() {
    return factory -> {
      var definition = (org.springframework.beans.factory.support.AbstractBeanDefinition) factory.getBeanDefinition("transactionAttributeSource");
      definition.setFactoryBeanName(null);
      definition.setFactoryMethodName(null);
      definition.setBeanClass(Extension.class);
      definition.setInstanceSupplier(Extension::new);
    };
  }
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner verifyActualSource(probe.persistence.infrastructure.InventoryAdapter inventory) {
    return args -> {
      int count = 0;
      for (var advisor : ((org.springframework.aop.framework.Advised)inventory).getAdvisors()) {
        if (advisor.getAdvice() instanceof org.springframework.transaction.interceptor.TransactionInterceptor interceptor) {
          count++;
          if (interceptor.getTransactionAttributeSource().getClass() != Extension.class) throw new AssertionError("extension is not actual source");
        }
      }
      if (count != 1) throw new AssertionError("actual source witness is ambiguous");
    };
  }
}
''')
        report = self.command("SG_PERSISTENCE_CALIBRATION")
        self.assertTrue(report["native_observation"]["started"])
        self.assertNotIn("SG_PERSISTENCE_DATA", [item["code"] for item in report["diagnostics"]])
        self.repaired_persistence()
        self.command()


class DatasourceIsolationCommands(unittest.TestCase):
    def setUp(self):
        import test_persistence_boundaries

        self.boundary = test_persistence_boundaries.PersistenceBoundaryCommands()
        self.boundary.setUp()
        self.addCleanup(self.boundary.doCleanups)

    def source(self, role, annotation=""):
        self.boundary.source(role, annotation=annotation)
        pom = self.boundary.role.case.project / "pom.xml"
        dependency = '<dependency><groupId>com.baomidou</groupId><artifactId>dynamic-datasource-spring</artifactId><version>4.5.0</version></dependency>'
        pom.write_text(pom.read_text(encoding="utf-8").replace("<dependencies>", "<dependencies>" + dependency), encoding="utf-8")

    def test_application_cannot_select_a_datasource_by_native_ds_annotation(self):
        self.source("application", '@com.baomidou.dynamic.datasource.annotation.DS("inventory")')
        self.boundary.role.command("SG_TECHNICAL_DEPENDENCY")
        self.boundary.role.case.repaired_project()
        self.source("application")
        self.boundary.role.command()

    def test_domain_cannot_hide_ds_annotation_by_lowering_visibility_and_infrastructure_can_use_it(self):
        self.source("domain", '@com.baomidou.dynamic.datasource.annotation.DS("inventory")')
        path = self.boundary.role.case.project / "src/test-named-production/java/probe/Service.java"
        path.write_text(path.read_text(encoding="utf-8").replace("public class Service", "class Service"), encoding="utf-8")
        self.boundary.role.case.definition["modules"][0].update(contract_types=[], contract_members=[])
        self.boundary.role.command("SG_TECHNICAL_DEPENDENCY")
        self.boundary.role.case.repaired_project()
        self.source("infrastructure", '@com.baomidou.dynamic.datasource.annotation.DS("inventory")')
        self.boundary.role.command()



class PersistenceObservationNeutralityCommands(PersistenceFixtures):
    def test_native_advice_subclass_is_a_calibration_gap_not_a_pass(self):
        self.write("src/test-named-production/java/probe/persistence/CustomAdvice.java", '''package probe.persistence;
/** 原生 Advice 的子类仍须单独校准，不由返回的原生来源代证。 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
public class CustomAdvice {
  public static class Extension extends org.springframework.transaction.interceptor.TransactionInterceptor {}
  @org.springframework.context.annotation.Bean
  public static org.springframework.beans.factory.config.BeanFactoryPostProcessor replaceAdvice() {
    return factory -> {
      var definition = (org.springframework.beans.factory.support.AbstractBeanDefinition) factory.getBeanDefinition("transactionInterceptor");
      definition.setFactoryBeanName(null);
      definition.setFactoryMethodName(null);
      definition.setBeanClass(Extension.class);
      definition.setInstanceSupplier(Extension::new);
      definition.getPropertyValues().add("transactionAttributeSource", new org.springframework.beans.factory.config.RuntimeBeanReference("transactionAttributeSource"));
    };
  }
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner verifyActualAdvice(probe.persistence.infrastructure.InventoryAdapter inventory) {
    return args -> {
      int count = 0;
      for (var advisor : ((org.springframework.aop.framework.Advised)inventory).getAdvisors()) {
        if (advisor.getAdvice() instanceof org.springframework.transaction.interceptor.TransactionInterceptor interceptor) {
          count++;
          if (interceptor.getClass() != Extension.class) throw new AssertionError("extension is not actual advice");
        }
      }
      if (count != 1) throw new AssertionError("actual advice witness is ambiguous");
    };
  }
}
''')
        report = self.command("SG_PERSISTENCE_CALIBRATION")
        self.assertTrue(report["native_observation"]["started"])
        self.assertNotIn("SG_PERSISTENCE_DATA", [item["code"] for item in report["diagnostics"]])
        self.repaired_persistence()
        self.command()

    def test_native_source_with_custom_parser_is_a_calibration_gap_not_a_pass(self):
        self.write("src/test-named-production/java/probe/persistence/CustomParser.java", '''package probe.persistence;
/** 来源类相同不表示使用默认解析器，自定义解析策略须报告未校准。 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
public class CustomParser {
  public static class Extension extends org.springframework.transaction.annotation.SpringTransactionAnnotationParser {}
  @org.springframework.context.annotation.Bean
  public static org.springframework.beans.factory.config.BeanFactoryPostProcessor replaceParser() {
    return factory -> {
      var definition = (org.springframework.beans.factory.support.AbstractBeanDefinition) factory.getBeanDefinition("transactionAttributeSource");
      definition.setFactoryBeanName(null);
      definition.setFactoryMethodName(null);
      definition.setBeanClass(org.springframework.transaction.annotation.AnnotationTransactionAttributeSource.class);
      definition.setInstanceSupplier(() -> {
        var source = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource(new Extension());
        source.addDefaultRollbackRule(org.springframework.transaction.interceptor.RollbackRuleAttribute.ROLLBACK_ON_ALL_EXCEPTIONS);
        return source;
      });
    };
  }
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner verifyActualParser(probe.persistence.infrastructure.InventoryAdapter inventory) {
    return args -> {
      int count = 0;
      var field = org.springframework.transaction.annotation.AnnotationTransactionAttributeSource.class.getDeclaredField("annotationParsers");
      if (!field.trySetAccessible()) throw new AssertionError("parser witness unavailable");
      for (var advisor : ((org.springframework.aop.framework.Advised)inventory).getAdvisors()) {
        if (advisor.getAdvice() instanceof org.springframework.transaction.interceptor.TransactionInterceptor interceptor) {
          count++;
          var source = interceptor.getTransactionAttributeSource();
          if (source.getClass() != org.springframework.transaction.annotation.AnnotationTransactionAttributeSource.class
              || !((java.util.Set<?>)field.get(source)).stream().allMatch(parser -> parser.getClass() == Extension.class))
            throw new AssertionError("extension is not actual parser");
        }
      }
      if (count != 1) throw new AssertionError("actual parser witness is ambiguous");
    };
  }
}
''')
        report = self.command("SG_PERSISTENCE_CALIBRATION")
        self.assertTrue(report["native_observation"]["started"])
        self.assertNotIn("SG_PERSISTENCE_DATA", [item["code"] for item in report["diagnostics"]])
        self.repaired_persistence()
        self.command()

    def test_observer_cannot_manufacture_rejection_by_opening_a_connection_before_native_execution(self):
        self.write("src/test-named-production/java/probe/persistence/CacheConfiguration.java", '''package probe.persistence;
/** 故意从应用自身拦截器放行未知别名，观察器不得替应用制造拒绝。 */
@org.springframework.context.annotation.Configuration
public class CacheConfiguration {
  @org.springframework.context.annotation.Bean
  public org.apache.ibatis.plugin.Interceptor cacheBypass() { return new CacheBypass(); }
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner witness(probe.persistence.infrastructure.UnknownAdapter unknown) {
    return args -> { if (!"master".equals(unknown.read())) throw new IllegalStateException("native cache witness failed"); };
  }
  /** 应用自身拦截器故意不取得物理连接，验证观察器透明性。 */
  @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class, method="query",
      args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}))
  public static class CacheBypass implements org.apache.ibatis.plugin.Interceptor {
    public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
      if ("missing".equals(com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder.peek())) {
        return java.util.List.of(new probe.persistence.infrastructure.GateRow(100L,"master"));
      }
      return invocation.proceed();
    }
  }
}
''')
        report = self.command("SG_PERSISTENCE_EXECUTION")
        self.assertTrue(report["native_observation"]["started"], "启动前未装观察器的见证必须真实通过")
        self.assertIsNone(next(item for item in report["native_observation"]["operations"] if item["scenario"] == "unknown")["exception"],
                          "不能由观察器提前取连接替应用产生正确异常")
        self.repaired_persistence()
        self.command()


class PersistenceIntegrityCommands(PersistenceFixtures):
    def test_same_row_count_cannot_hide_wrong_persisted_values(self):
        path = self.project / "src/test-named-production/java/probe/persistence/infrastructure/InventoryAdapter.java"
        path.write_text(path.read_text(encoding="utf-8").replace('new GateRow(1L, "committed")', 'new GateRow(1L, "wrong")'), encoding="utf-8")
        report = self.command("SG_PERSISTENCE_DATA")
        self.assertEqual(report["native_observation"]["operations"][2]["rows"]["inventory"], 2)
        self.repaired_persistence()
        self.command()

    def test_secret_output_and_changed_production_class_cannot_be_public_proof(self):
        self.write("src/test-named-production/java/probe/persistence/Leak.java", '''package probe.persistence;
/** 故意输出本轮合成凭证，不接触真实部署密钥。 */
@org.springframework.context.annotation.Configuration
public class Leak {
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner leakRunner() { return args -> System.out.println(System.getenv("DF_GATE_INVENTORY_PASSWORD")); }
}
''')
        self.command("SG_PERSISTENCE_SECRET_OUTPUT")
        self.repaired_persistence()
        self.write("src/test-named-production/java/probe/persistence/Drift.java", '''package probe.persistence;
/** 故意改变已经加载的生产类产物，不修改维护源码。 */
@org.springframework.context.annotation.Configuration
public class Drift {
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.ApplicationRunner driftRunner() {
    return args -> java.nio.file.Files.write(java.nio.file.Path.of(App.class.getResource("App.class").toURI()), new byte[]{0}, java.nio.file.StandardOpenOption.APPEND);
  }
}
''')
        self.command("SG_INPUT_CHANGED")
        self.repaired_persistence()
        self.command()


class PersistenceInputCommands(PersistenceFixtures):
    def test_contract_cannot_remove_or_weaken_required_scenarios(self):
        original = json.loads(json.dumps(self.definition))
        for change in ("schema", "omitted", "duplicated", "default-source", "zero-write", "rollback-committed", "no-transaction", "table"):
            with self.subTest(change=change):
                self.definition = json.loads(json.dumps(original))
                if change == "schema":
                    self.definition["schema"] = True
                elif change == "omitted":
                    self.definition["operations"].pop()
                elif change == "duplicated":
                    self.definition["operations"][1] = self.definition["operations"][0]
                elif change == "default-source":
                    self.definition["operations"][1].update(database="master", lookup="")
                elif change == "zero-write":
                    self.definition["operations"][2]["result"] = "0"
                elif change == "rollback-committed":
                    self.definition["operations"][3]["rows"]["inventory"].append(["2", "rolled-back"])
                elif change == "no-transaction":
                    self.definition["operations"][3]["transaction"] = False
                else:
                    self.definition["data"]["table"] = "gate_record; DROP TABLE gate_record"
                self.command("SG_PERSISTENCE_INPUT")
                self.assertFalse((self.project / "target").exists())
        self.definition = original
        self.command()

    def test_private_connections_cannot_alias_one_real_database_twice(self):
        original = {"schema": 1, "databases": self.peers}
        self.private.chmod(0o644)
        self.command("SG_PERSISTENCE_CONNECTION")
        self.assertFalse((self.project / "target").exists())
        self.private.chmod(0o600)
        self.private.write_text(json.dumps({"schema": 1, "databases": {"master": self.peers["master"], "inventory": self.peers["master"]}}), encoding="utf-8")
        self.command("SG_PERSISTENCE_CONNECTION")
        self.assertFalse((self.project / "target").exists())
        self.private.write_text(json.dumps(original), encoding="utf-8")
        self.command()

    def test_missing_native_tools_cannot_pass_then_repairs(self):
        tools, self.persistence_tools = self.persistence_tools, str(self.root / "missing-tools")
        self.command("SG_PERSISTENCE_TOOLS")
        self.assertFalse((self.project / "target").exists())
        self.persistence_tools = tools
        self.command()


class PersistenceDeclarationChecks(unittest.TestCase):
    def contract(self):
        initial = {"master": [["100", "master"]], "inventory": [["1", "A"], ["1", "A"], ["2", "B"]]}
        committed = dict(initial, inventory=[*initial["inventory"], ["3", "C"]])
        operations = []
        for scenario in ("primary", "secondary", "commit", "rollback", "unknown", "restored-primary"):
            write = scenario in {"commit", "rollback"}
            database = None if scenario == "unknown" else "master" if scenario in {"primary", "restored-primary"} else "inventory"
            error = "java.lang.IllegalStateException" if scenario == "rollback" else "com.baomidou.dynamic.datasource.exception.CannotFindDataSourceException" if scenario == "unknown" else None
            operations.append({"scenario": scenario, "bean": "adapter", "method": "run", "lookup": "missing" if database is None else "" if database == "master" else "inventory",
                               "database": database, "transaction": write, "result": None if error else "1" if write else database, "exception": error,
                               "statement": "probe.Mapper." + ("insert" if write else "selectById"), "rows": initial if scenario in {"primary", "secondary"} else committed})
        return {"schema": 1, "application": "probe.App", "datasource": "dataSource", "factory": "sqlSessionFactory", "manager": "transactionManager",
                "data": {"schema": "public", "table": "gate_record", "columns": ["id", "label"]}, "operations": operations}

    def invoke(self, source):
        import calibrate_persistence

        with tempfile.TemporaryDirectory(prefix="domain-forge-row-contract-") as directory:
            path = pathlib.Path(directory) / "persistence.json"
            path.write_text(json.dumps(source), encoding="utf-8")
            return calibrate_persistence.declaration(path)

    def test_preserved_duplicate_old_rows_are_allowed(self):
        source = self.contract()
        self.assertEqual(self.invoke(source), source)

    def test_supported_checked_exception_does_not_allow_arbitrary_or_deleted_failure(self):
        from calibrate_source import ProbeFailure

        source = self.contract()
        source["operations"][3]["exception"] = "java.io.IOException"
        self.assertEqual(self.invoke(source), source)
        for failure in (None, "java.lang.Exception", "java.sql.SQLException"):
            with self.subTest(failure=failure), self.assertRaises(ProbeFailure) as error:
                source["operations"][3]["exception"] = failure
                self.invoke(source)
            self.assertEqual(error.exception.diagnostic["code"], "SG_PERSISTENCE_INPUT")

    def test_lost_old_instance_and_two_new_instances_are_rejected(self):
        from calibrate_source import ProbeFailure

        source = self.contract()
        for operation in source["operations"][2:]:
            operation["rows"]["inventory"] = [["1", "A"], ["2", "B"], ["3", "C"], ["3", "C"]]
        with self.assertRaises(ProbeFailure) as error:
            self.invoke(source)
        self.assertEqual(error.exception.diagnostic["code"], "SG_PERSISTENCE_INPUT")


class PersistenceObservationChecks(unittest.TestCase):
    def test_native_observation_cannot_omit_identity_execution_or_state(self):
        import calibrate_persistence
        from calibrate_source import ProbeFailure

        credentials = {name: {"jdbc_url": "jdbc:postgresql://127.0.0.1:" + port + "/gate"} for name, port in (("master", "55431"), ("inventory", "55432"))}
        document = ET.Element("persistence-observation", started="true")
        resources = ET.SubElement(document, "resources", {name: "true" for name in ("supported", "mybatis-plus", "factory-bound", "manager-bound")})
        resources.set("lookup", "null")
        for name in ("primary", "master", "inventory"):
            ET.SubElement(resources, "pool", {"name": name, "jdbc-url": credentials["master" if name == "primary" else name]["jdbc_url"]})
        source = {"operations": []}
        initial = {"master": [["100", "master"]], "inventory": [["100", "inventory"]]}
        committed = dict(initial, inventory=[*initial["inventory"], ["1", "committed"]])
        for name in calibrate_persistence.SCENARIOS:
            transactional, failed = name in {"commit", "rollback"}, name in {"rollback", "unknown"}
            alias = None if name == "unknown" else "master" if name in {"primary", "restored-primary"} else "inventory"
            lookup = "missing" if alias is None else "" if alias == "master" else "inventory"
            error = "java.lang.IllegalStateException" if name == "rollback" else "com.baomidou.dynamic.datasource.exception.CannotFindDataSourceException" if failed else None
            result = None if failed else "1" if name == "commit" else alias
            rows = initial if name in {"primary", "secondary"} else committed
            declared = {"scenario": name, "database": alias, "lookup": lookup, "transaction": transactional, "exception": error, "result": result,
                        "statement": "probe.GateMapper." + ("insert" if transactional else "selectById"), "rows": rows}
            source["operations"].append(declared)
            operation = ET.SubElement(document, "operation", {"scenario": name, "lookup-after": "null", "default-rollback": "true" if transactional else "not-transactional"})
            if failed:
                operation.set("exception", error)
                ET.SubElement(operation, "failure", {"class": error})
            else:
                operation.set("result-null", "false")
                operation.set("result", result)
            trace = ET.SubElement(operation, "trace", {"statement": declared["statement"], "command": "INSERT" if transactional else "SELECT", "lookup": lookup,
                                                       "transaction": str(transactional).lower(), "completed": "false" if name == "unknown" else "true",
                                                       "prepared": "0" if name == "unknown" else "1"})
            if alias:
                trace.set("jdbc-url", credentials[alias]["jdbc_url"])
                trace.set("auto-commit", str(not transactional).lower())
            for database, values in rows.items():
                snapshot = ET.SubElement(operation, "snapshot", name=database)
                for row in values:
                    observed = ET.SubElement(snapshot, "row")
                    for value in row:
                        ET.SubElement(observed, "value", null="false").text = value
        original = ET.tostring(document)
        self.assertEqual(calibrate_persistence.assess(document, source, credentials), [])
        for change, diagnostic in (("startup", "SG_PERSISTENCE_OBSERVATION"), ("resource", "SG_PERSISTENCE_RESOURCE"),
                                   ("transaction", "SG_PERSISTENCE_TRANSACTION"), ("operation", "SG_PERSISTENCE_COVERAGE"),
                                   ("trace", "SG_PERSISTENCE_EXECUTION"), ("pool", "SG_PERSISTENCE_RESOURCE"), ("null", "SG_PERSISTENCE_OBSERVATION"),
                                   ("default-policy", "SG_PERSISTENCE_OBSERVATION"), ("default-config", "SG_PERSISTENCE_DEFAULT_ROLLBACK"),
                                   ("default-unsupported", "SG_PERSISTENCE_CALIBRATION")):
            with self.subTest(change=change):
                document = ET.fromstring(original)
                if change == "startup":
                    del document.attrib["started"]
                elif change == "resource":
                    del document.find("resources").attrib["factory-bound"]
                elif change == "transaction":
                    del document.find("resources").attrib["manager-bound"]
                elif change == "operation":
                    document.remove(document.findall("operation")[-1])
                elif change == "trace":
                    document.find("operation").remove(document.find("operation/trace"))
                elif change == "pool":
                    document.find("resources").append(ET.fromstring(ET.tostring(resources.find("pool"))))
                elif change == "default-policy":
                    del document.find("operation").attrib["default-rollback"]
                elif change == "default-config":
                    document.findall("operation")[2].set("default-rollback", "false")
                elif change == "default-unsupported":
                    document.find("operation").set("default-rollback", "unsupported")
                else:
                    del document.find("operation/snapshot/row/value").attrib["null"]
                if diagnostic == "SG_PERSISTENCE_OBSERVATION":
                    with self.assertRaises(ProbeFailure) as error:
                        calibrate_persistence.assess(document, source, credentials)
                    self.assertEqual(error.exception.diagnostic["code"], diagnostic)
                else:
                    self.assertIn(diagnostic, [item["code"] for item in calibrate_persistence.assess(document, source, credentials)])


class PersistenceToolQualityChecks(unittest.TestCase):
    def test_native_observer_keeps_owned_source_quality(self):
        tools = os.environ.get("DOMAIN_FORGE_GATE_TOOLS", "")
        self.assertTrue(tools, "观察器质量必须提供真实源码工具，不跳过")
        sources = [str(HERE / name) for name in ("PersistenceInventory.java", "ResourceRoutingFixture.java")]
        for action in ("comments", "method-presence", "format", "complexity"):
            command = [sys.executable, "-B", str(HERE / "calibrate_source.py"), "--tools", tools, action]
            if action in ("comments", "complexity"):
                command.extend(["--purpose", "production"])
            result = subprocess.run([*command, *sources], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(set(json.loads(result.stdout)["scanned"]), set(sources))


class PersistencePartitionChecks(unittest.TestCase):
    def test_fixed_persistence_groups_cover_every_case_once_and_match_ci(self):
        catalog = json.loads((HERE.parent / "control_plane/catalog.json").read_text(encoding="utf-8"))
        workflow = (HERE.parents[1] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        module, loader = sys.modules[__name__], unittest.TestLoader()

        def identifiers(suite):
            if isinstance(suite, unittest.TestCase):
                return [suite.id()]
            return [identifier for item in suite for identifier in identifiers(item)]

        complete = identifiers(loader.loadTestsFromModule(module))
        self.assertEqual(len(complete), len(set(complete)), "持久化模块不得重复加载用例")
        selected = set()
        for check, names in (("persistence-behavior-tests", ["PersistenceBehaviorCommands", "PersistenceToolQualityChecks", "PersistencePartitionChecks"]),
                             ("persistence-routing-tests", ["PersistenceRoutingCommands"]), ("persistence-transaction-tests", ["PersistenceTransactionCommands"]),
                             ("persistence-execution-tests", ["PersistenceExecutionCommands"]), ("persistence-context-tests", ["PersistenceContextCommands"]),
                             ("persistence-binding-tests", ["PersistenceBindingCommands"]),
                             ("persistence-rollback-tests", ["PersistenceRollbackCommands"]),
                             ("persistence-policy-tests", ["PersistencePolicyCommands"]),
                             ("persistence-policy-source-tests", ["PersistencePolicySourceCommands"]),
                             ("persistence-integrity-tests", ["PersistenceIntegrityCommands"]),
                             ("persistence-observer-tests", ["PersistenceObservationNeutralityCommands"]),
                             ("persistence-input-tests", ["PersistenceInputCommands", "PersistenceDeclarationChecks", "PersistenceObservationChecks"]),
                             ("datasource-isolation-tests", ["DatasourceIsolationCommands"])):
            self.assertIn(check, catalog["checks"], "持久化校准分组未登记")
            self.assertEqual(catalog["checks"][check]["argv"], ["@python", "-B", "tools/engineering_gate/test_persistence.py", *names], "持久化分组命令偏离登记")
            self.assertTrue(catalog["checks"][check]["requires_test_count"])
            environment = "calibrate_roles.py" if check == "datasource-isolation-tests" else "calibrate_persistence.py"
            self.assertEqual(catalog["checks"][check]["environment_argv"], ["@python", "-B", "tools/engineering_gate/" + environment, "--environment"], "持久化环境预检偏离固定入口")
            self.assertEqual(workflow.splitlines().count("        run: python -B tools/engineering_gate/test_persistence.py " + " ".join(names)), 1, "持久化 CI 入口缺失或重复")
            group = identifiers(loader.loadTestsFromNames(names, module))
            self.assertTrue(group)
            self.assertEqual(len(group), len(set(group)), "持久化分组内用例重复")
            self.assertFalse(selected & set(group), "持久化跨组用例重复")
            selected.update(group)
        self.assertEqual(selected, set(complete), "持久化分组遗漏用例")


if __name__ == "__main__":
    unittest.main()
