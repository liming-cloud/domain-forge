"""固定合成合同的持久化具体适配；不接受自报通过或任意 SQL 期望。"""

import base64
import json
import os
import pathlib
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter

import calibrate_flyway
import calibrate_maven_source
import calibrate_persistence
from calibrate_source import HERE, digest, execute, require


PROFILES = {
    "mapping": [("roundtrip", False), ("rounding", False), ("overflow", False), ("keyRollback", False), ("afterFailure", False)],
    "pagination": [(name, False) for name in ("firstPage", "secondPage", "emptyPage", "boundValue", "oversized", "negativeSize")],
    "concurrency": [("competingVersion", True), ("conditions", False), ("competingUnique", True)],
    "batch": [(name, False) for name in ("batchCommit", "batchFlushFailure", "batchCommitFailure", "batchOversized")],
    "migration": [],
}


def configuration():
    return dict(calibrate_persistence.configuration(), **{name: digest(HERE / name) for name in
                ("calibrate_persistence_adapters.py", "PersistenceAdapterInventory.java")})


def declaration(path):
    source = calibrate_flyway.read_json(path, "SG_ADAPTER_INPUT")
    fields = {"schema", "application", "bean", "factory", "datasource", "manager", "profile"}
    if source.get("profile") == "migration":
        fields |= {"phase", "migrations"}
    require(set(source) == fields and type(source["schema"]) is int and source["schema"] == 1 and
            source["profile"] in PROFILES, "SG_ADAPTER_INPUT", "须选择完整固定适配合同，不接受自定义期望或删除场景")
    require(isinstance(source["application"], str) and re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", source["application"]),
            "SG_ADAPTER_INPUT", "实际应用入口须明确")
    for name in ("bean", "factory", "datasource", "manager"):
        require(isinstance(source[name], str) and re.fullmatch(r"[A-Za-z_$][\w$]*", source[name]), "SG_ADAPTER_INPUT", "实际资源须明确")
    if source["profile"] == "migration":
        require(source["phase"] in {"initialize", "restart", "upgrade", "failure", "recovery"} and
                isinstance(source["migrations"], dict) and set(source["migrations"]) == {"master", "inventory"}, "SG_ADAPTER_INPUT", "多源迁移阶段与来源须明确")
        for alias, migrations in source["migrations"].items():
            require(isinstance(migrations, list) and len(migrations) == (1 if source["phase"] in {"initialize", "restart"} else 2), "SG_ADAPTER_INPUT", "不能省略目标迁移")
            for index, item in enumerate(migrations, 1):
                resource = "db/" + alias + "/V" + str(index) + ("__init.sql" if index == 1 else "__upgrade.sql")
                require(isinstance(item, dict) and set(item) == {"resource", "sha256"} and item["resource"] == resource and
                        isinstance(item["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]), "SG_ADAPTER_INPUT", "固定迁移顺序与独立来源摘要不能偏离")
    return source


def mapped_row(identifier, quantity):
    return [str(identifier), "A", quantity, "2024-02-29", "2024-02-29 12:34:56.123456", "2024-02-29 04:34:56.123456+00", None, "R", "0"]


def seed_rows(profile):
    if profile == "pagination":
        return [[str(i), "A" if i < 4 else "B", str(i) + ".00", None, None, None, None, None, "0"] for i in range(1, 6)]
    if profile == "concurrency":
        return [["1", "A", "100.00", None, None, None, None, None, "0"]]
    return []


def expected_cases(profile):
    """固定合成验收值不从被测方法或当次数据库读取后生成。"""
    rows = seed_rows(profile)
    cases = {"initial": ([], rows, [], [])}
    if profile == "mapping":
        one = [mapped_row(1, "9999.99")]
        three = [*one, mapped_row(2, "1.24"), mapped_row(3, "-1.24")]
        cases.update(roundtrip=(["1|9999.99|2024-02-29|2024-02-29T12:34:56.123456|2024-02-29T04:34:56.123456Z|null|true"], one, [], []),
                     rounding=(["1.24|-1.24"], three, [], []), overflow=(["!22003"], three, [], []), keyRollback=(["!java.lang.IllegalStateException"], three, [], []),
                     afterFailure=(["5"], [*three, mapped_row(5, "2.00")], [], []))
    elif profile == "pagination":
        for name, result in zip((item[0] for item in PROFILES[profile]), ("3|1:2,2:1", "3|3:0", "3|", "0|", "!java.lang.IllegalArgumentException", "!java.lang.IllegalArgumentException")):
            cases[name] = ([result], rows, [], [])
    elif profile == "concurrency":
        updated = [["1", "A", "101.00", None, None, None, None, None, "1"]]
        cases.update(competingVersion=(["1:1", "0:1"], updated, [], []), conditions=(["0:1:0"], updated, [], []),
                     competingUnique=(["1", "!23505"], updated, [["ONE"]], []))
    elif profile == "batch":
        saved = [["1", "A"], ["2", "B"], ["3", "C"]]
        for name, result in zip((item[0] for item in PROFILES[profile]), ("3", "!23505", "!23505", "!java.lang.IllegalArgumentException")):
            cases[name] = ([result], [], [], saved)
    return cases


def rows(snapshot):
    return [[None if value.get("null") == "true" else value.text or "" for value in row.findall("v")] for row in snapshot.findall("row")]


def assess(document, source, credentials):
    if source["profile"] == "migration":
        return assess_migration(document, source, credentials)
    errors = []
    def check(condition, code, message):
        if not condition:
            errors.append({"code": code, "message": message})
    check(document.get("started") == "true", "SG_ADAPTER_START", "实际应用未完成启动")
    resource = document.find("resources")
    check(resource is not None and all(resource.get(name) == "true" for name in ("mybatis-plus", "factory-bound", "manager-bound")), "SG_ADAPTER_RESOURCE", "真实会话、事务和动态数据源未绑定")
    expected = expected_cases(source["profile"])
    cases = document.findall("case")
    check([case.get("name") for case in cases] == list(expected), "SG_ADAPTER_COVERAGE", "实际场景必须完整且各执行一次")
    for case in cases:
        name = case.get("name")
        if name not in expected:
            continue
        results, records, unique, batch = expected[name]
        calls = case.findall("call")
        actual = ["!" + (call.get("sqlstate") or call.get("exception")) if call.get("exception") else call.get("result") for call in calls]
        check(Counter(actual) == Counter(results), "SG_ADAPTER_RESULT", name + "：实际返回、冲突或类型化异常不符合独立合同")
        check(all(call.get("lookup") == "null" for call in calls), "SG_ADAPTER_RESOURCE", name + "：调用后路由未恢复")
        snapshots = case.findall("snapshot")
        check([(s.get("database"), s.get("table")) for s in snapshots] == [(alias, table) for alias in credentials for table in ("gate_adapter", "gate_unique", "gate_batch")], "SG_ADAPTER_COVERAGE", name + "：数据库状态观察缺失或重复")
        for snapshot in snapshots:
            table = snapshot.get("table")
            values = {"gate_adapter": records, "gate_unique": unique, "gate_batch": batch}.get(table, []) if snapshot.get("database") == "master" else []
            check(snapshot.get("complete") == "true" and rows(snapshot) == values, "SG_ADAPTER_DATA", name + "：真实提交状态、隔离或有限行值不符")
            if table == "gate_adapter":
                columns = snapshot.findall("column")
                check(len(columns) == 9 and columns[2].get("precision") == "6" and columns[2].get("scale") == "2" and
                      [c.get("type") for c in columns] == ["bigserial", "text", "numeric", "date", "timestamp", "timestamptz", "text", "text", "int4"],
                      "SG_ADAPTER_MAPPING", "实际列类型、精度或标度尚未符合合同")
        assess_execution(name, calls, source["profile"], credentials, check)
    return errors


def assess_execution(name, calls, profile, credentials, check):
    if name == "initial":
        return
    rejected = name in {"oversized", "negativeSize", "batchOversized"}
    for call in calls:
        traces = call.findall("trace")
        physical = [trace for trace in traces if trace.get("jdbc-url")]
        check(not traces if rejected else bool(physical), "SG_ADAPTER_EXECUTION", name + "：必需原生执行缺失或拒绝发生前已执行 SQL")
        check(all(trace.get("jdbc-url") == credentials["master"]["jdbc_url"] for trace in physical), "SG_ADAPTER_RESOURCE", name + "：实际语句执行目标错误")
        commands = Counter(trace.get("command") for trace in traces if trace.get("statement"))
        expected = {"roundtrip": {"INSERT": 1, "SELECT": 1}, "rounding": {"INSERT": 2, "SELECT": 2},
                    "overflow": {"INSERT": 1}, "keyRollback": {"INSERT": 1}, "afterFailure": {"INSERT": 1, "SELECT": 1},
                    "competingVersion": {"SELECT": 1, "UPDATE": 1}, "conditions": {"UPDATE": 3}, "competingUnique": {"INSERT": 1},
                    "batchCommit": {"INSERT": 3}, "batchFlushFailure": {"INSERT": 3}, "batchCommitFailure": {"INSERT": 2}}
        if name in expected:
            check(commands == Counter(expected[name]), "SG_ADAPTER_EXECUTION", name + "：实际 Mapper 操作覆盖不符，不能由直接 JDBC 或固定返回代替")
        if profile != "pagination":
            check(all(trace.get("transaction") == "true" and trace.get("auto-commit") == "false" for trace in physical), "SG_ADAPTER_TRANSACTION", name + "：真实写入不在应用事务内")
        if profile == "concurrency":
            check(all(trace.get("isolation") == "2" for trace in physical), "SG_ADAPTER_TRANSACTION", name + "：实际隔离配置不是本次声明的 Read Committed")
            affected = [trace.get("affected-rows") for trace in traces if trace.get("method") == "update"]
            expected_rows = {"conditions": ["0", "1", "0"],
                             "competingVersion": [(call.get("result") or "").split(":")[0]],
                             "competingUnique": [None if call.get("exception") else "1"]}[name]
            check(affected == expected_rows, "SG_ADAPTER_AFFECTED_ROWS", name + "：原生逐次影响行数不符合合同，不能由应用固定返回代替")
        if profile == "batch" and not rejected:
            check(any(trace.get("batch") == "true" for trace in traces), "SG_ADAPTER_BATCH", "必须观察原生 BATCH，而非逐条执行冒充批处理")
            successful = [trace for trace in traces if trace.get("flush-counts")]
            check(bool(successful), "SG_ADAPTER_BATCH", "首批成功刷新没有原生计数")
            if name == "batchCommit":
                check([trace.get("flush-counts") for trace in successful] == ["1,1", "1"], "SG_ADAPTER_BATCH", "实际分批和刷新结果未完整核对")
    if name in {"competingVersion", "competingUnique"}:
        backends = [{trace.get("backend") for trace in call.findall("trace") if trace.get("backend")} for call in calls]
        check(len(backends) == 2 and all(len(value) == 1 for value in backends) and backends[0].isdisjoint(backends[1]), "SG_ADAPTER_CONCURRENCY", "竞争必须来自两个不同实际数据库会话")


def assess_migration(document, source, credentials):
    errors, phase = [], source["phase"]
    def check(condition, message):
        if not condition:
            errors.append({"code": "SG_ADAPTER_MIGRATION", "message": message})
    check(document.get("started") == ("false" if phase == "failure" else "true"), "实际启动结果不符合阶段合同")
    if phase == "failure":
        check(document.get("sqlstate") == "23514", "失败升级须有真实约束失败，不接受工具或连接失败")
        failure, declared = document.find("migration-failure"), source["migrations"]["inventory"][1]
        check(failure is not None and failure.get("resource") == declared["resource"] and
              failure.get("resource-sha256") == declared["sha256"], "失败须来自已声明的实际 Flyway 迁移脚本，不接受无关启动失败")
    else:
        resource = document.find("resources")
        check(resource is not None and all(resource.get(name) == "true" for name in ("mybatis-plus", "factory-bound", "manager-bound")), "实际持久化装配缺失")
        flyways = document.findall("flyway")
        check([item.get("database") for item in flyways] == list(credentials), "两个实际 Flyway 资源须完整观察")
        for item in flyways:
            alias = item.get("database")
            check(alias in credentials and item.get("jdbc-url") == credentials[alias]["jdbc_url"] and item.get("valid") == "true", "迁移 Bean 与数据库目标不符或原生历史校验失败")
            declared = source["migrations"].get(alias, [])
            actual = item.findall("migration")
            check([(m.get("version"), m.get("script"), m.get("state")) for m in actual] ==
                  [(str(i), pathlib.PurePosixPath(m["resource"]).name, "SUCCESS") for i, m in enumerate(declared, 1)], "迁移版本、来源或执行状态不符")
            for actual_migration, declared_migration in zip(actual, declared):
                check(actual_migration.get("resource-sha256") == declared_migration["sha256"] and
                      actual_migration.get("resource") == declared_migration["resource"], "实际迁移资源与独立摘要不符")
    snapshots = document.findall("snapshot")
    check([(s.get("database"), s.get("table")) for s in snapshots] == [(alias, table) for alias in credentials for table in ("flyway_schema_history", "gate_marker")], "多源独立历史和数据观察缺失或重复")
    for snapshot in snapshots:
        alias = snapshot.get("database")
        upgraded = phase in {"upgrade", "recovery"} or phase == "failure" and alias == "master"
        if snapshot.get("table") == "flyway_schema_history":
            expected = [["1", "V1__init.sql", "t"]] + ([["2", "V2__upgrade.sql", "t"]] if upgraded else [])
        else:
            expected = [["1", alias + (":v2" if upgraded else ":v1")]]
            if phase != "initialize":
                expected.append(["99", "retained"])
            if upgraded:
                expected = [row + ["2"] for row in expected]
        check(snapshot.get("complete") == "true" and rows(snapshot) == expected, "目标历史、实际结构或保留数据不符；不假定两个资源共同回滚")
    return errors


def observe(args, source, credentials, compilation):
    with tempfile.TemporaryDirectory(prefix="domain-forge-adapter-private-") as directory:
        private = pathlib.Path(directory)
        classes = private / "classes"
        classes.mkdir()
        request, observed = private / "request.xml", private / "observed.xml"
        root = ET.Element("adapter-contract", {key: str(value) for key, value in source.items() if key != "migrations"})
        if source["profile"] == "migration":
            for alias, migrations in source["migrations"].items():
                for migration in migrations:
                    ET.SubElement(root, "migration", database=alias, **migration)
        for method, parallel in PROFILES[source["profile"]]:
            ET.SubElement(root, "operation", name=method, parallel=str(parallel).lower())
        ET.ElementTree(root).write(request, encoding="utf-8", xml_declaration=True)
        classpath = os.pathsep.join(compilation["classpath"])
        compiled = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), str(HERE / "PersistenceAdapterInventory.java")])
        (pathlib.Path(args.project) / "target/adapter-observer-compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
        require(compiled.returncode == 0, "SG_ADAPTER_TOOL_FAILURE", "观察器自身编译失败，不是有效反例")
        child = dict(os.environ)
        for alias, value in credentials.items():
            for field, suffix in (("jdbc_url", "URL"), ("username", "USER"), ("password", "PASSWORD")):
                child["DF_GATE_" + alias.upper() + "_" + suffix] = value[field]
        runtime = subprocess.run(["java", "-cp", os.pathsep.join([str(classes), classpath]), "PersistenceAdapterInventory", str(request), str(observed)], capture_output=True, text=True, timeout=25, env=child)
        secrets = [form for value in credentials.values() for form in (value["password"], base64.b64encode(value["password"].encode()).decode())]
        require(all(value not in runtime.stdout + runtime.stderr for value in secrets), "SG_ADAPTER_SECRET_OUTPUT", "实际输出包含本轮合成凭据")
        (pathlib.Path(args.project) / "target/adapter-runtime.log").write_text(runtime.stdout + runtime.stderr, encoding="utf-8")
        require(runtime.returncode == 0 and observed.is_file() and observed.stat().st_size <= 262144, "SG_ADAPTER_RUNTIME", "实际原生观察未完成，不能据此证明违规")
        content = observed.read_text(encoding="utf-8")
        require(all(value not in content for value in secrets), "SG_ADAPTER_SECRET_OUTPUT", "私有观察包含不必要凭据")
        return ET.fromstring(content)


def probe(args):
    before, env = configuration(), calibrate_persistence.environment(args)
    path, private = pathlib.Path(args.adapters), pathlib.Path(args.connections)
    source, source_sha = declaration(path), digest(path)
    credentials, private_sha = calibrate_persistence.connections(private, env["database_environment"]["docker_endpoint"]), digest(private)
    credentials = {alias: credentials[alias] for alias in ("master", "inventory")}
    build, compilation = calibrate_persistence.compile_application(args, source["application"])
    versions = {(item["group"], item["artifact"]): item["version"] for item in compilation["dependencies"]}
    require(versions.get(("com.baomidou", "mybatis-plus-jsqlparser")) == "3.5.17" and versions.get(("com.github.jsqlparser", "jsqlparser")) == "5.2", "SG_ADAPTER_CALIBRATION", "实际应用分页依赖必须是已校准组合")
    if source["profile"] == "migration":
        lock = calibrate_flyway.baseline()
        require(all(versions.get((group, name)) == version for group, name, version in
                    (("org.springframework.boot", "spring-boot-flyway", lock["boot_version"]),
                     ("org.flywaydb", "flyway-core", lock["flyway_version"]),
                     ("org.flywaydb", "flyway-database-postgresql", lock["flyway_version"]))),
                "SG_ADAPTER_CALIBRATION", "实际迁移应用组合尚未校准，不能用工具目录版本替代应用依赖")
        for migrations in source["migrations"].values():
            for migration in migrations:
                original = pathlib.Path(args.project) / "src/main/resources" / migration["resource"]
                copied = pathlib.Path(compilation["output"]) / migration["resource"]
                require(original.is_file() and copied.is_file() and digest(original) == digest(copied) == migration["sha256"], "SG_ADAPTER_INPUT", "实际生产迁移资源须匹配独立来源摘要")
    document = observe(args, source, credentials, compilation)
    errors = assess(document, source, credentials)
    calibrate_maven_source.unchanged_build(build, pathlib.Path(args.project).resolve())
    require(before == configuration() and source_sha == digest(path) and private_sha == digest(private) and env == calibrate_persistence.environment(args), "SG_INPUT_CHANGED", "适配运行期间维护输入或原生产产物改变")
    native = {"started": document.get("started") == "true", "cases": [{"name": case.get("name"), "calls": len(case.findall("call")), "traces": len(case.findall("call/trace")), "snapshots": len(case.findall("snapshot"))} for case in document.findall("case")]}
    return {"ok": not errors, "proof_scope": "isolated-persistence-adapter-" + source["profile"], "configuration": before, "environment": env, "build": build,
            "native_observation": native, "diagnostics": errors, "adapters_checked": not errors, "product_verified": False, "human_acceptance": False, "full_persistence_verified": False}
