"""真实 MyBatis-Plus 与动态数据源的隔离路由、事务校准；不执行产品业务。"""

import argparse
import base64
import json
import os
import pathlib
import re
import stat
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter

import calibrate_flyway
import calibrate_maven
import calibrate_maven_source
from calibrate_source import HERE, ProbeFailure, digest, execute, require


SCENARIOS = ("primary", "secondary", "commit", "rollback", "unknown", "restored-primary")


def baseline():
    lock = calibrate_flyway.read_json(HERE / "persistence.lock.json", "SG_PERSISTENCE_CALIBRATION")
    require(type(lock.get("schema")) is int and lock["schema"] == 1 and
            lock["migration_lock_sha256"] == digest(HERE / "flyway.lock.json") and
            lock["mybatis_plus_version"] == "3.5.17" and lock["mybatis_version"] == "3.5.19" and lock["mybatis_spring_version"] == "4.0.0" and lock["spring_version"] == "7.0.9" and
            lock["dynamic_datasource_version"] == "4.5.0" and lock["jsqlparser_version"] == "5.2",
            "SG_PERSISTENCE_CALIBRATION", "需要固定 MyBatis-Plus、动态数据源与原生数据库校准输入")
    return lock


def configuration():
    return {name: digest(HERE / name) for name in ("persistence.lock.json", "calibrate_persistence.py", "PersistenceInventory.java", "calibrate_flyway.py", "flyway.lock.json")}


def framework_pom():
    lock = baseline()
    dependencies = "".join('<dependency><groupId>com.baomidou</groupId><artifactId>' + artifact + '</artifactId><version>' + version + '</version></dependency>' for artifact, version in
                           (("mybatis-plus-spring-boot4-starter", lock["mybatis_plus_version"]), ("mybatis-plus-jsqlparser", lock["mybatis_plus_version"]), ("dynamic-datasource-spring-boot4-starter", lock["dynamic_datasource_version"])))
    return calibrate_flyway.framework_pom().removesuffix("</dependencies>") + dependencies + "</dependencies>"


def environment(args):
    target = pathlib.Path(args.persistence_tools or "")
    require(args.persistence_tools and target.is_dir() and not target.is_symlink(), "SG_PERSISTENCE_TOOLS", "缺少固定持久化依赖，不跳过")
    manifest = calibrate_flyway.read_json(target / "manifest.json", "SG_PERSISTENCE_TOOLS")
    require(type(manifest.get("schema")) is int and manifest["schema"] == 1 and manifest.get("lock_sha256") == digest(HERE / "persistence.lock.json"), "SG_PERSISTENCE_TOOLS", "持久化工具基线不符")
    require(not any(path.is_symlink() for path in target.rglob("*")), "SG_PERSISTENCE_TOOLS", "依赖目录不接受符号链接")
    artifacts = {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}
    lock = baseline()
    require(artifacts and artifacts == manifest.get("artifacts") and all("dependencies/" + name + "-" + version + ".jar" in artifacts for name, version in
            (("mybatis-plus-spring-boot4-starter", lock["mybatis_plus_version"]), ("mybatis-plus-core", lock["mybatis_plus_version"]),
             ("dynamic-datasource-spring-boot4-starter", lock["dynamic_datasource_version"]), ("dynamic-datasource-spring", lock["dynamic_datasource_version"]),
             ("mybatis-plus-jsqlparser", lock["mybatis_plus_version"]), ("jsqlparser", lock["jsqlparser_version"]))),
            "SG_PERSISTENCE_TOOLS", "原生持久化依赖缺失、增加或改变")
    require(all("dependencies/" + name + "-" + version + ".jar" in artifacts for name, version in
                (("mybatis", lock["mybatis_version"]), ("mybatis-spring", lock["mybatis_spring_version"]),
                 ("spring-jdbc", lock["spring_version"]), ("spring-tx", lock["spring_version"]))),
            "SG_PERSISTENCE_TOOLS", "原生桥接或事务依赖缺失")
    return {"ok": True, "proof_scope": "isolated-persistence-environment-only", "persistence_tools": str(target.resolve()),
            "artifacts": artifacts, "database_environment": calibrate_flyway.environment(args), "diagnostics": []}


def prepare(args):
    calibrate_flyway.environment(args)
    target = pathlib.Path(args.prepare)
    require(target.is_dir() and not target.is_symlink() and not any(target.iterdir()) and
            not target.resolve().is_relative_to(HERE.parents[1]), "SG_PERSISTENCE_TOOLS", "准备需要仓库外专用空目录")
    home, _ = calibrate_maven.runtime(args.maven_home)
    repository = pathlib.Path(args.repository or "")
    require(args.repository and repository.is_dir() and not repository.is_symlink(), "SG_PERSISTENCE_TOOLS", "需要专用 Maven 仓库")
    settings = target / "settings.xml"
    settings.write_text('<settings><mirrors><mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>', encoding="utf-8")
    pom = target / "pom.xml"
    pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>probe</groupId><artifactId>persistence-environment</artifactId><version>1.0.0</version>' + framework_pom() + '</project>', encoding="utf-8")
    command = [str(home / "bin/mvn"), "-B", "-ntp", "-s", str(settings), "-gs", str(settings), "-Dmaven.repo.local=" + str(repository.resolve()), "-f", str(pom),
               "org.apache.maven.plugins:maven-dependency-plugin:" + calibrate_flyway.baseline()["dependency_plugin_version"] + ":copy-dependencies", "-DincludeScope=runtime", "-DoutputDirectory=" + str(target / "dependencies")]
    with (target / "prepare.log").open("w", encoding="utf-8") as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, text=True, timeout=30)
    require(result.returncode == 0, "SG_PERSISTENCE_TOOLS", "原生持久化依赖准备失败，保留真实日志")
    manifest = {"schema": 1, "lock_sha256": digest(HERE / "persistence.lock.json"), "artifacts": {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}}
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    args.persistence_tools = str(target)
    return environment(args)


def declaration(path):
    source = calibrate_flyway.read_json(path, "SG_PERSISTENCE_INPUT")
    require(set(source) == {"schema", "application", "datasource", "factory", "manager", "data", "operations"} and type(source["schema"]) is int and source["schema"] == 1 and
            isinstance(source["application"], str) and re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", source["application"]),
            "SG_PERSISTENCE_INPUT", "需要独立声明实际应用、原生资源与场景，不从当前结果反推")
    for field in ("datasource", "factory", "manager"):
        require(isinstance(source[field], str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", source[field]), "SG_PERSISTENCE_INPUT", "资源名须明确")
    data = source["data"]
    require(isinstance(data, dict) and set(data) == {"schema", "table", "columns"} and
            all(isinstance(data[key], str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", data[key]) for key in ("schema", "table")) and
            isinstance(data["columns"], list) and len(data["columns"]) == 2 and len(set(data["columns"])) == 2 and
            all(isinstance(value, str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value) for value in data["columns"]),
            "SG_PERSISTENCE_INPUT", "合成结果只接受明确 Schema、表及两列，不接收任意查询")
    require(isinstance(source["operations"], list) and [item.get("scenario") for item in source["operations"] if isinstance(item, dict)] == list(SCENARIOS),
            "SG_PERSISTENCE_INPUT", "必需路由、提交、回滚、未知别名与恢复场景不能省略或重复")
    for item in source["operations"]:
        require(set(item) == {"scenario", "bean", "method", "lookup", "database", "transaction", "result", "exception", "statement", "rows"} and
                all(isinstance(item[key], str) and re.fullmatch(r"[A-Za-z_$][\w$]*", item[key]) for key in ("bean", "method")) and
                isinstance(item["lookup"], str) and re.fullmatch(r"[A-Za-z0-9_]*", item["lookup"]) and
                item["database"] in {None, "master", "inventory"} and type(item["transaction"]) is bool and
                (item["result"] is None or isinstance(item["result"], str)) and (item["exception"] is None or isinstance(item["exception"], str)) and
                isinstance(item["statement"], str) and re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", item["statement"]),
                "SG_PERSISTENCE_INPUT", "场景、实际 Mapper 语句及期望结果必须独立完整")
        expected_failure = item["exception"]
        allowed_failures = {"rollback": {"java.lang.IllegalStateException", "java.io.IOException"}, "unknown": {"com.baomidou.dynamic.datasource.exception.CannotFindDataSourceException"}}
        require(expected_failure in allowed_failures.get(item["scenario"], {None}) and (item["database"] is None) == (item["scenario"] == "unknown") and
                (item["scenario"] not in {"commit", "rollback"} or item["transaction"]) and
                (item["scenario"] != "unknown" or not item["transaction"]) and (item["result"] is None) == (expected_failure is not None),
                "SG_PERSISTENCE_INPUT", "拒绝场景不能伪装成功或删除实际事务责任")
        expected_database = None if item["scenario"] == "unknown" else "master" if item["scenario"] in {"primary", "restored-primary"} else "inventory"
        expected_lookup = "missing" if item["scenario"] == "unknown" else "" if expected_database == "master" else "inventory"
        require(item["database"] == expected_database and item["lookup"] == expected_lookup and
                (item["scenario"] != "commit" or item["result"] == "1"), "SG_PERSISTENCE_INPUT", "校准意图不能把目标源改成主库或把零行写入当成功")
        rows = item["rows"]
        require(isinstance(rows, dict) and set(rows) == {"master", "inventory"} and all(isinstance(values, list) and len(values) <= 100 and
                all(isinstance(row, list) and len(row) == 2 and all(value is None or isinstance(value, str) for value in row) for row in values) for values in rows.values()),
                "SG_PERSISTENCE_INPUT", "每步须独立声明两个数据库的有限行值")
    rows = [item["rows"] for item in source["operations"]]
    previous = Counter(map(tuple, rows[0]["inventory"]))
    committed = Counter(map(tuple, rows[2]["inventory"]))
    require(rows[0] == rows[1] and rows[2]["master"] == rows[0]["master"] and
            previous <= committed and (committed - previous).total() == 1 and
            all(value == rows[2] for value in rows[3:]), "SG_PERSISTENCE_INPUT", "提交须保留旧行并新增一行；回滚及拒绝的期望不能改为已经写入")
    return source


def connections(path, endpoint):
    require(path.is_file() and not path.is_symlink() and stat.S_IMODE(path.stat().st_mode) == 0o600, "SG_PERSISTENCE_CONNECTION", "连接须使用权限 0600 的普通私有文件")
    source = calibrate_flyway.read_json(path, "SG_PERSISTENCE_CONNECTION")
    require(set(source) == {"schema", "databases"} and type(source["schema"]) is int and source["schema"] == 1 and isinstance(source["databases"], dict) and
            set(source["databases"]) == {"master", "inventory"}, "SG_PERSISTENCE_CONNECTION", "仅接受两个本轮专用合成数据库")
    actual = {name: calibrate_flyway.owned_connection(value, endpoint) for name, value in source["databases"].items()}
    require(len({value["container"] for value in actual.values()}) == 2 and len({value["jdbc_url"] for value in actual.values()}) == 2,
            "SG_PERSISTENCE_CONNECTION", "两个别名不能用同一实际数据库冒充隔离")
    return actual


def assess(document, source, credentials):
    require(document.tag == "persistence-observation" and document.get("started") in {"true", "false"},
            "SG_PERSISTENCE_OBSERVATION", "缺少真实启动状态，不能以空观察通过")
    if document.get("started") == "false":
        return [{"code": "SG_PERSISTENCE_STARTUP"}]
    resources = document.findall("resources")
    require(len(resources) == 1, "SG_PERSISTENCE_OBSERVATION", "原生资源观察缺失或重复")
    resources, errors = resources[0], []
    if any(resources.get(name) != "true" for name in ("supported", "mybatis-plus", "factory-bound")):
        errors.append({"code": "SG_PERSISTENCE_RESOURCE"})
    if resources.get("manager-bound") != "true":
        errors.append({"code": "SG_PERSISTENCE_TRANSACTION"})
    pools = resources.findall("pool")
    expected = {name: value["jdbc_url"] for name, value in credentials.items()}
    expected["primary"] = credentials["master"]["jdbc_url"]
    if len(pools) != len(expected) or {item.get("name"): item.get("jdbc-url") for item in pools} != expected:
        errors.append({"code": "SG_PERSISTENCE_RESOURCE"})
    if resources.get("lookup") not in {"null", ""}:
        errors.append({"code": "SG_PERSISTENCE_CONTEXT"})
    operations = document.findall("operation")
    if [item.get("scenario") for item in operations] != list(SCENARIOS):
        errors.append({"code": "SG_PERSISTENCE_COVERAGE"})
    declared = {item["scenario"]: item for item in source["operations"]}
    for operation in operations:
        item = declared.get(operation.get("scenario"))
        if item is not None:
            errors.extend(assess_operation(operation, item, credentials))
    return errors


def assess_operation(operation, item, credentials):
    errors = []
    policy = operation.get("default-rollback")
    require(policy in {"true", "false", "unsupported", "not-transactional"}, "SG_PERSISTENCE_OBSERVATION", "缺少当前操作实际事务默认规则观察")
    if policy == "unsupported" or item["transaction"] and policy != "true":
        errors.append({"code": "SG_PERSISTENCE_CALIBRATION" if policy == "unsupported" else "SG_PERSISTENCE_DEFAULT_ROLLBACK", "scenario": item["scenario"]})
    if operation.get("lookup-after") not in {"null", ""}:
        errors.append({"code": "SG_PERSISTENCE_CONTEXT"})
    failures = [value.get("class") for value in operation.findall("failure")]
    if item["exception"] is None:
        if operation.get("exception") is not None or failures or operation.get("result-null") != "false" or operation.get("result") != item["result"]:
            errors.append({"code": "SG_PERSISTENCE_RESULT", "scenario": item["scenario"]})
    elif not operation.get("exception") or item["exception"] not in failures or operation.get("result") is not None or operation.get("result-null") is not None:
        errors.append({"code": "SG_PERSISTENCE_RESULT", "scenario": item["scenario"]})
    traces = operation.findall("trace")
    if len(traces) != 1 or any(value.get("statement") != item["statement"] or value.get("command") != ("INSERT" if item["scenario"] in {"commit", "rollback"} else "SELECT") or
                               value.get("completed") != ("false" if item["scenario"] == "unknown" else "true") or
                               value.get("prepared") != ("0" if item["scenario"] == "unknown" else "1") for value in traces):
        errors.append({"code": "SG_PERSISTENCE_EXECUTION", "scenario": item["scenario"]})
    for trace in traces:
        require(trace.get("transaction") in {"true", "false"} and trace.get("completed") in {"true", "false"},
                "SG_PERSISTENCE_OBSERVATION", "实际语句的事务与执行状态须明确")
        expected_url = credentials[item["database"]]["jdbc_url"] if item["database"] else None
        lookup = "" if trace.get("lookup") == "null" else trace.get("lookup")
        if trace.get("jdbc-url") != expected_url or lookup != item["lookup"]:
            errors.append({"code": "SG_PERSISTENCE_ROUTE", "scenario": item["scenario"]})
        if (trace.get("transaction") == "true") != item["transaction"] or item["transaction"] and trace.get("auto-commit") != "false":
            errors.append({"code": "SG_PERSISTENCE_TRANSACTION", "scenario": item["scenario"]})
    snapshots = operation.findall("snapshot")
    if len(snapshots) != 2 or {value.get("name") for value in snapshots} != {"master", "inventory"}:
        errors.append({"code": "SG_PERSISTENCE_COVERAGE", "scenario": item["scenario"]})
    for snapshot in snapshots:
        if snapshot.get("name") not in item["rows"]:
            continue
        require(all(len(row.findall("value")) == 2 and all(value.get("null") in {"true", "false"} for value in row.findall("value")) for row in snapshot.findall("row")),
                "SG_PERSISTENCE_OBSERVATION", "每行须完整观察两列及空值标记")
        rows = [[None if value.get("null") == "true" else value.text or "" for value in row.findall("value")] for row in snapshot.findall("row")]
        canonical = lambda values: sorted(json.dumps(row, ensure_ascii=False) for row in values)
        if snapshot.findall("query-failure") or canonical(rows) != canonical(item["rows"][snapshot.get("name")]):
            errors.append({"code": "SG_PERSISTENCE_DATA", "scenario": item["scenario"]})
    return errors


def observer_input(path, source):
    root = ET.Element("persistence-contract", {key: str(value) for key, value in source.items() if key not in {"data", "operations"}})
    ET.SubElement(root, "data", **dict(source["data"], columns=",".join(source["data"]["columns"])))
    for item in source["operations"]:
        ET.SubElement(root, "operation", {key: value for key, value in item.items() if key in {"scenario", "bean", "method"}})
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def observe(args, source, credentials, compilation, env):
    with tempfile.TemporaryDirectory(prefix="domain-forge-persistence-private-") as directory:
        private, child = pathlib.Path(directory), dict(os.environ)
        classes = private / "classes"
        classes.mkdir()
        request, observed = private / "request.xml", private / "observed.xml"
        observer_input(request, source)
        classpath = os.pathsep.join(compilation["classpath"])
        compiled = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), str(HERE / "PersistenceInventory.java")])
        (pathlib.Path(args.project) / "target/persistence-observer-compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
        require(compiled.returncode == 0, "SG_PERSISTENCE_TOOL_FAILURE", "原生观察器编译失败，不是持久化反例")
        for name, value in credentials.items():
            for field, suffix in (("jdbc_url", "URL"), ("username", "USER"), ("password", "PASSWORD")):
                child["DF_GATE_" + name.upper() + "_" + suffix] = value[field]
        runtime = subprocess.run(["java", "-cp", os.pathsep.join([str(classes), classpath]), "PersistenceInventory", str(request), str(observed)],
                                 capture_output=True, text=True, timeout=30, env=child)
        secrets = [form for value in credentials.values() for form in (value["password"], base64.b64encode(value["password"].encode()).decode())]
        require(all(value not in runtime.stdout + runtime.stderr for value in secrets), "SG_PERSISTENCE_SECRET_OUTPUT", "实际进程输出含本轮合成数据库凭证")
        require(runtime.returncode == 0 and observed.is_file() and observed.stat().st_size <= 65536, "SG_PERSISTENCE_RUNTIME", "实际观察失败，不以工具或环境错误证明违规")
        content = observed.read_text(encoding="utf-8")
        require(all(value not in content for value in secrets), "SG_PERSISTENCE_SECRET_OUTPUT", "原生观察不能复制本轮合成凭证")
        return ET.fromstring(content)


def compile_application(args, application):
    """共享实际构建与框架身份检查，不以观察器依赖补齐被测应用。"""
    build = calibrate_maven.probe(args)
    compilation = next(item for item in build["compilations"] if item["purpose"] == "production")
    require((pathlib.Path(compilation["output"]) / (application.replace(".", "/") + ".class")).is_file(), "SG_PERSISTENCE_ENTRY", "入口须来自本次生产编译")
    lock = baseline()
    versions = {(item["group"], item["artifact"]): item["version"] for item in compilation["dependencies"]}
    require(all(versions.get(("com.baomidou", name)) == version for name, version in
                (("mybatis-plus-spring-boot4-starter", lock["mybatis_plus_version"]), ("mybatis-plus-core", lock["mybatis_plus_version"]),
                 ("dynamic-datasource-spring-boot4-starter", lock["dynamic_datasource_version"]), ("dynamic-datasource-spring", lock["dynamic_datasource_version"]))),
            "SG_PERSISTENCE_CALIBRATION", "实际生产依赖尚未校准，不以工具依赖补齐")
    require(all(versions.get((group, name)) == version for group, name, version in
                (("org.mybatis", "mybatis", lock["mybatis_version"]), ("org.mybatis", "mybatis-spring", lock["mybatis_spring_version"]),
                 ("org.springframework.boot", "spring-boot", calibrate_flyway.baseline()["boot_version"]),
                 ("org.postgresql", "postgresql", calibrate_flyway.baseline()["driver_version"]),
                 ("org.springframework", "spring-jdbc", lock["spring_version"]), ("org.springframework", "spring-tx", lock["spring_version"]))),
            "SG_PERSISTENCE_CALIBRATION", "实际 Boot、MyBatis 事务桥接或驱动版本尚未校准")
    return build, compilation


def probe(args):
    before, env = configuration(), environment(args)
    path, private = pathlib.Path(args.persistence), pathlib.Path(args.connections)
    source, source_sha = declaration(path), digest(path)
    credentials, private_sha = connections(private, env["database_environment"]["docker_endpoint"]), digest(private)
    build, compilation = compile_application(args, source["application"])
    document = observe(args, source, credentials, compilation, env)
    errors = assess(document, source, credentials)
    native = {"started": document.get("started") == "true", "operations": [{"scenario": item.get("scenario"), "exception": item.get("exception"),
              "trace_count": len(item.findall("trace")), "executions": [{"database": next((name for name, value in credentials.items() if value["jdbc_url"] == trace.get("jdbc-url")), None),
              "transaction": trace.get("transaction") == "true", "prepared": trace.get("prepared") == "1", "completed": trace.get("completed") == "true"} for trace in item.findall("trace")],
              "rows": {snapshot.get("name"): len(snapshot.findall("row")) for snapshot in item.findall("snapshot")}} for item in document.findall("operation")]}
    calibrate_maven_source.unchanged_build(build, pathlib.Path(args.project).resolve())
    require(before == configuration() and source_sha == digest(path) and private_sha == digest(private) and env == environment(args), "SG_INPUT_CHANGED", "执行期间输入、原生产产物或工具改变")
    return {"ok": not errors, "proof_scope": "isolated-mybatis-dynamic-routing-transaction-only", "configuration": before, "environment": env,
            "build": build, "native_observation": native, "diagnostics": errors, "routing_and_transactions_checked": not errors,
            "full_persistence_verified": False, "product_verified": False, "human_acceptance": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--prepare")
    operation.add_argument("--environment", action="store_true")
    parser.add_argument("--persistence")
    parser.add_argument("--adapters")
    parser.add_argument("--connections")
    parser.add_argument("--persistence-tools", default=os.environ.get("DOMAIN_FORGE_GATE_PERSISTENCE_TOOLS"))
    parser.add_argument("--migration-tools", default=os.environ.get("DOMAIN_FORGE_GATE_MIGRATION_TOOLS"))
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    args = parser.parse_args()
    if args.project and not (bool(args.persistence) != bool(args.adapters) and args.connections):
        parser.error("隔离校准须提供独立持久化合同与两个私有合成连接")
    try:
        if args.project and args.adapters:
            import calibrate_persistence_adapters
            result = calibrate_persistence_adapters.probe(args)
        else:
            result = prepare(args) if args.prepare else {"ok": True, "environment": environment(args), "diagnostics": []} if args.environment else probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.TimeoutExpired) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_PERSISTENCE_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
