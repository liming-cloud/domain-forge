"""Boot 4 启动 SQL 迁移的隔离 PostgreSQL 校准；不执行产品迁移或修复历史。"""

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

import calibrate_maven
import calibrate_maven_source
from calibrate_source import HERE, ProbeFailure, digest, execute, require


def read_json(path, code="SG_FLYWAY_INPUT"):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, code, "JSON 字段不能重复")
            result[key] = value
        return result
    require(path.is_file() and not path.is_symlink(), code, "需要普通 JSON 文件")
    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    require(isinstance(value, dict), code, "输入根必须为对象")
    return value


def baseline():
    lock = read_json(HERE / "flyway.lock.json")
    require(type(lock.get("schema")) is int and lock["schema"] == 1 and lock["boot_version"] == "4.0.8" and
            lock["flyway_version"] == "11.14.1" and lock["driver_version"] == "42.7.13" and
            re.fullmatch(r"postgres@sha256:[0-9a-f]{64}", lock["image"]),
            "SG_FLYWAY_CALIBRATION", "需要固定 Boot 4 配套依赖与不可变数据库镜像")
    return lock


def configuration():
    return {name: digest(HERE / name) for name in ("flyway.lock.json", "calibrate_flyway.py", "FlywayInventory.java")}


def check_docker_routing():
    require(not any(os.environ.get(name) for name in ("DOCKER_HOST", "DOCKER_CONTEXT")),
            "SG_FLYWAY_ENVIRONMENT", "校准不接受隐式 Docker 路由覆盖，不清除后冒充原环境")


def local_endpoint():
    check_docker_routing()
    result = execute(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"])
    require(result.returncode == 0 and result.stdout.strip().startswith("unix://"),
            "SG_FLYWAY_ENVIRONMENT", "当前只校准本地 Unix Docker，不操作远端守护进程")
    return result.stdout.strip()


def docker(*arguments, endpoint=None):
    check_docker_routing()
    result = execute(["docker", "--host", endpoint or local_endpoint(), *arguments])
    require(result.returncode == 0, "SG_FLYWAY_ENVIRONMENT", "实际本地 Docker 环境不可用，不跳过数据库验证")
    return result.stdout.strip()


def environment(args):
    target = pathlib.Path(args.migration_tools or "")
    require(args.migration_tools and target.is_dir() and not target.is_symlink(),
            "SG_FLYWAY_TOOLS", "缺少固定原生迁移依赖")
    manifest = read_json(target / "manifest.json")
    require(manifest.get("schema") == 1 and manifest.get("lock_sha256") == digest(HERE / "flyway.lock.json") and
            isinstance(manifest.get("artifacts"), dict) and manifest["artifacts"], "SG_FLYWAY_TOOLS", "迁移资料基线不符")
    require(not any(path.is_symlink() for path in target.rglob("*")), "SG_FLYWAY_TOOLS", "依赖目录不接受符号链接")
    artifacts = {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}
    lock = baseline()
    require(artifacts == manifest["artifacts"] and all("dependencies/" + name + "-" + version + ".jar" in artifacts for name, version in
            (("flyway-core", lock["flyway_version"]), ("flyway-database-postgresql", lock["flyway_version"]),
             ("postgresql", lock["driver_version"]), ("spring-boot-flyway", lock["boot_version"]))),
            "SG_FLYWAY_TOOLS", "原生依赖缺失、增加或改变")
    endpoint = local_endpoint()
    images = json.loads(docker("image", "inspect", lock["image"], "--format", "{{json .RepoDigests}}", endpoint=endpoint))
    require(lock["image"] in images, "SG_FLYWAY_ENVIRONMENT", "实际数据库镜像摘要不符")
    return {"ok": True, "proof_scope": "isolated-flyway-environment-only", "migration_tools": str(target.resolve()),
            "artifacts": artifacts, "image": lock["image"], "docker_endpoint": endpoint,
            "docker": docker("version", "--format", "{{.Server.Version}}", endpoint=endpoint),
            "maven": calibrate_maven.environment(args)["environment"], "diagnostics": []}


def framework_pom():
    lock = baseline()
    dependencies = "".join("<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId></dependency>" for group, artifact in
                           (("org.springframework.boot", "spring-boot-starter-jdbc"), ("org.springframework.boot", "spring-boot-starter-flyway"),
                            ("org.flywaydb", "flyway-database-postgresql"), ("org.postgresql", "postgresql")))
    return '<dependencyManagement><dependencies><dependency><groupId>org.springframework.boot</groupId>' \
           '<artifactId>spring-boot-dependencies</artifactId><version>' + lock["boot_version"] + '</version>' \
           '<type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement><dependencies>' + dependencies + '</dependencies>'


def prepare(args):
    endpoint = local_endpoint()
    target = pathlib.Path(args.prepare)
    require(target.is_dir() and not target.is_symlink() and not any(target.iterdir()) and
            not target.resolve().is_relative_to(HERE.parents[1]), "SG_FLYWAY_TOOLS", "准备需要仓库外专用空目录")
    home, _ = calibrate_maven.runtime(args.maven_home)
    repository = pathlib.Path(args.repository or "")
    require(args.repository and repository.is_dir() and not repository.is_symlink(), "SG_FLYWAY_TOOLS", "需要专用 Maven 仓库")
    lock = baseline()
    settings = target / "settings.xml"
    settings.write_text('<settings><mirrors><mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf>'
                        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>', encoding="utf-8")
    pom = target / "pom.xml"
    pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>probe</groupId>'
                   '<artifactId>flyway-environment</artifactId><version>1.0.0</version>' + framework_pom() + '</project>', encoding="utf-8")
    command = [str(home / "bin/mvn"), "-B", "-ntp", "-s", str(settings), "-gs", str(settings), "-Dmaven.repo.local=" + str(repository.resolve()),
               "-f", str(pom), "org.apache.maven.plugins:maven-dependency-plugin:" + lock["dependency_plugin_version"] + ":copy-dependencies",
               "-DincludeScope=runtime", "-DoutputDirectory=" + str(target / "dependencies")]
    with (target / "prepare.log").open("w", encoding="utf-8") as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, text=True, timeout=30)
    require(result.returncode == 0, "SG_FLYWAY_TOOLS", "原生迁移依赖准备失败；保留日志，不视为已准备")
    manifest = {"schema": 1, "lock_sha256": digest(HERE / "flyway.lock.json"),
                "artifacts": {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}}
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    if execute(["docker", "--host", endpoint, "image", "inspect", lock["image"]]).returncode != 0:
        docker("pull", lock["image"], endpoint=endpoint)
    args.migration_tools = str(target)
    return environment(args)


def declaration(path):
    source = read_json(path)
    require(set(source) == {"schema", "application", "datasource", "flyway", "database_schema", "target_version", "migrations", "data"} and
            type(source["schema"]) is int and source["schema"] == 1 and
            isinstance(source["application"], str) and re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", source["application"]),
            "SG_FLYWAY_INPUT", "需独立声明应用、实际资源和目标迁移，不从当前结果反推期望")
    for field in ("datasource", "flyway", "database_schema"):
        require(isinstance(source[field], str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", source[field]), "SG_FLYWAY_INPUT", "资源名称须明确")
    require(isinstance(source["target_version"], str) and re.fullmatch(r"[0-9]+(?:[._][0-9]+)*", source["target_version"]) and
            isinstance(source["migrations"], list) and source["migrations"], "SG_FLYWAY_INPUT", "目标版本及必要 SQL 不能为空")
    identities = set()
    for item in source["migrations"]:
        require(isinstance(item, dict) and set(item) == {"version", "source", "resource", "sha256"} and isinstance(item["version"], str) and
                re.fullmatch(r"[0-9]+(?:[._][0-9]+)*", item["version"]) and item["version"] not in identities and
                isinstance(item["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]), "SG_FLYWAY_INPUT", "SQL 版本、来源及独立摘要须唯一完整")
        for field in ("source", "resource"):
            require(isinstance(item[field], str) and item[field].endswith(".sql") and not pathlib.PurePosixPath(item[field]).is_absolute() and
                    ".." not in pathlib.PurePosixPath(item[field]).parts and "\\" not in item[field], "SG_FLYWAY_INPUT", "SQL 路径须在声明工程内")
        identities.add(item["version"])
    require(source["target_version"] in identities, "SG_FLYWAY_INPUT", "目标版本必须有必要迁移")
    data = source["data"]
    require(isinstance(data, dict) and set(data) == {"table", "columns", "types", "nullable", "rows"} and isinstance(data["table"], str) and
            re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", data["table"]) and isinstance(data["columns"], list) and 0 < len(data["columns"]) <= 16 and
            all(isinstance(value, str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value) for value in data["columns"]) and
            len(set(data["columns"])) == len(data["columns"]) and isinstance(data["types"], list) and len(data["types"]) == len(data["columns"]) and
            all(isinstance(value, str) and value for value in data["types"]) and isinstance(data["nullable"], list) and
            len(data["nullable"]) == len(data["columns"]) and all(type(value) is bool for value in data["nullable"]) and
            isinstance(data["rows"], list) and len(data["rows"]) <= 100 and
            all(isinstance(row, list) and len(row) == len(data["columns"]) and all(value is None or isinstance(value, str) for value in row) for row in data["rows"]),
            "SG_FLYWAY_INPUT", "独立合成数据期望须非空列、有界且不接受任意 SQL")
    return source


def connection(path, endpoint=None):
    require(path.is_file() and not path.is_symlink() and stat.S_IMODE(path.stat().st_mode) == 0o600,
            "SG_FLYWAY_CONNECTION", "合成数据库连接须使用权限 0600 的普通私有文件")
    source = read_json(path)
    return owned_connection(source, endpoint)


def owned_connection(source, endpoint=None):
    require(set(source) == {"schema", "container", "jdbc_url", "username", "password"} and type(source["schema"]) is int and source["schema"] == 1 and
            isinstance(source["container"], str) and re.fullmatch(r"[0-9a-f]{64}", source["container"]) and
            isinstance(source["jdbc_url"], str) and re.fullmatch(r"jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/gate", source["jdbc_url"]) and
            source["username"] == "gate" and isinstance(source["password"], str) and len(source["password"]) >= 16,
            "SG_FLYWAY_CONNECTION", "只接受本轮专用回环合成数据库，不接受产品凭证或任意地址")
    check_docker_routing()
    actual = execute(["docker", "--host", endpoint or local_endpoint(), "inspect", source["container"]])
    require(actual.returncode == 0, "SG_FLYWAY_CONNECTION", "声明的临时数据库容器不存在")
    inspected = json.loads(actual.stdout)[0]
    ports = inspected.get("NetworkSettings", {}).get("Ports", {}).get("5432/tcp") or []
    require(inspected["Id"] == source["container"] and inspected["State"]["Running"] and
            inspected["Config"].get("Labels", {}).get("org.domainforge.calibration") == "flyway" and
            inspected["Config"]["Image"] == baseline()["image"] and inspected["HostConfig"]["AutoRemove"] and
            not inspected["HostConfig"].get("Binds") and not inspected["HostConfig"].get("Mounts") and
            set(inspected["HostConfig"].get("Tmpfs", {})) == {"/var/lib/postgresql"} and
            [(item.get("Type"), item.get("Destination")) for item in inspected.get("Mounts", [])] in ([], [("tmpfs", "/var/lib/postgresql")]) and
            len(ports) == 1 and ports[0]["HostIp"] == "127.0.0.1" and ":" + ports[0]["HostPort"] + "/gate" in source["jdbc_url"],
            "SG_FLYWAY_CONNECTION", "连接必须属于本轮无宿主数据挂载的临时校准容器")
    return source


def assess(document, source, credentials, output):
    require(document.tag == "flyway-observation" and document.get("started") in {"true", "false"},
            "SG_FLYWAY_OBSERVATION", "缺少实际启动状态，不能用空观察通过")
    if document.get("started") == "false":
        return [{"code": "SG_FLYWAY_STARTUP" if document.findall("native-failure") else "SG_FLYWAY_RUNTIME"}]
    databases, flyways = document.findall("database"), document.findall("flyway")
    require(len(databases) == 1 and len(flyways) <= 1, "SG_FLYWAY_OBSERVATION", "实际资源观察缺失或重复")
    database, lock, errors = databases[0], baseline(), []
    if database.get("jdbc-url") != credentials["jdbc_url"] or database.get("product") != "PostgreSQL" or \
            database.get("version") != lock["postgresql_version"] or database.get("driver") != lock["driver_version"]:
        errors.append({"code": "SG_FLYWAY_DATABASE"})
    if document.get("initializers") != "1" or not flyways:
        errors.append({"code": "SG_FLYWAY_COVERAGE"})
    else:
        flyway = flyways[0]
        if flyway.get("jdbc-url") != credentials["jdbc_url"]:
            errors.append({"code": "SG_FLYWAY_DATABASE"})
        if flyway.get("schema") != source["database_schema"] or flyway.get("managed-schemas") not in {"", source["database_schema"]}:
            errors.append({"code": "SG_FLYWAY_SCHEMA"})
        if flyway.get("valid") != "true":
            errors.append({"code": "SG_FLYWAY_VALIDATE"})
        actual = [(item.get("version"), item.get("script")) for item in flyway.findall("migration") if item.get("type") == "SQL"]
        expected = [(item["version"].replace("_", "."), pathlib.PurePosixPath(item["resource"]).name) for item in source["migrations"]]
        if len(actual) != len(set(actual)) or set(actual) != set(expected) or flyway.get("current") != source["target_version"].replace("_", "."):
            errors.append({"code": "SG_FLYWAY_COVERAGE"})
        declarations = {(item["version"].replace("_", "."), pathlib.PurePosixPath(item["resource"]).name): item for item in source["migrations"]}
        for item in flyway.findall("migration"):
            declared = declarations.get((item.get("version"), item.get("script")))
            if item.get("type") != "SQL" or declared is None:
                continue
            physical = pathlib.Path(item.get("physical") or "")
            expected_path = output / declared["resource"]
            if not physical.is_absolute() or not physical.is_file() or physical.is_symlink() or \
                    physical.resolve() != expected_path.resolve() or digest(physical) != declared["sha256"]:
                errors.append({"code": "SG_FLYWAY_RESOURCE"})
        if any(item.get("state") != "SUCCESS" or item.get("type") not in {"SQL", "SCHEMA"} for item in flyway.findall("migration")):
            errors.append({"code": "SG_FLYWAY_EXECUTION"})
    columns = [dict(item.attrib) for item in database.findall("column")]
    expected_columns = [{"name": name, "type": kind, "nullable": "1" if nullable else "0"}
                        for name, kind, nullable in zip(source["data"]["columns"], source["data"]["types"], source["data"]["nullable"])]
    if columns != expected_columns:
        errors.append({"code": "SG_FLYWAY_STRUCTURE"})
    require(all(value.get("null") in {"true", "false"} for row in database.findall("row") for value in row.findall("value")),
            "SG_FLYWAY_OBSERVATION", "数据观察须明确区分空值与字符串，不能猜测缺失字段")
    rows = [[None if value.get("null") == "true" else value.text or "" for value in row.findall("value")] for row in database.findall("row")]
    canonical = lambda values: sorted(json.dumps(row, ensure_ascii=False) for row in values)
    if database.findall("query-failure") or canonical(rows) != canonical(source["data"]["rows"]):
        errors.append({"code": "SG_FLYWAY_DATA"})
    return errors


def probe(args):
    before, env = configuration(), environment(args)
    path, private_path = pathlib.Path(args.migration), pathlib.Path(args.connection)
    source, source_sha = declaration(path), digest(path)
    credentials, connection_sha = connection(private_path, env["docker_endpoint"]), digest(private_path)
    build = calibrate_maven.probe(args)
    compilation = next(item for item in build["compilations"] if item["purpose"] == "production")
    output = pathlib.Path(compilation["output"])
    require((output / (source["application"].replace(".", "/") + ".class")).is_file(), "SG_FLYWAY_ENTRY", "入口不属于同次真实生产编译")
    versions = {(item["group"], item["artifact"]): item["version"] for item in compilation["dependencies"]}
    lock = baseline()
    require(all(versions.get((group, name)) == version for group, name, version in
                (("org.springframework.boot", "spring-boot-flyway", lock["boot_version"]), ("org.flywaydb", "flyway-core", lock["flyway_version"]),
                 ("org.flywaydb", "flyway-database-postgresql", lock["flyway_version"]), ("org.postgresql", "postgresql", lock["driver_version"]))),
            "SG_FLYWAY_CALIBRATION", "实际编译组合尚未校准，不以工具类路径补齐应用依赖")
    resources = {}
    for item in source["migrations"]:
        maintained, compiled = pathlib.Path(args.project) / item["source"], output / item["resource"]
        require(maintained.is_file() and compiled.is_file() and not maintained.is_symlink() and not compiled.is_symlink() and
                digest(maintained) == item["sha256"] == digest(compiled), "SG_FLYWAY_RESOURCE", "独立 SQL 期望与实际维护／编译资源不符")
        resources[str(compiled)] = digest(compiled)
    with tempfile.TemporaryDirectory(prefix="domain-forge-flyway-private-") as directory:
        private = pathlib.Path(directory)
        classes = private / "classes"
        classes.mkdir()
        observed = private / "observed.xml"
        classpath = os.pathsep.join(compilation["classpath"])
        compiled = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                            "-d", str(classes), str(HERE / "FlywayInventory.java")])
        (pathlib.Path(args.project) / "target/flyway-observer-compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
        require(compiled.returncode == 0, "SG_FLYWAY_TOOL_FAILURE", "观察器编译失败，不是迁移反例")
        child = dict(os.environ, DF_GATE_JDBC_URL=credentials["jdbc_url"], DF_GATE_DB_USER=credentials["username"], DF_GATE_DB_PASSWORD=credentials["password"])
        command = ["java", "-cp", os.pathsep.join([str(classes), classpath]), "FlywayInventory", source["application"], source["datasource"],
                   source["flyway"], source["database_schema"], source["data"]["table"], ",".join(source["data"]["columns"]), str(observed)]
        runtime = subprocess.run(command, capture_output=True, text=True, timeout=30, env=child)
        runtime_output = runtime.stdout + runtime.stderr
        require(all(value not in runtime_output for value in (credentials["password"], base64.b64encode(credentials["password"].encode()).decode())),
                "SG_FLYWAY_SECRET_OUTPUT", "实际运行输出包含本轮合成数据库凭证")
        require(runtime.returncode == 0 and observed.is_file() and observed.stat().st_size <= 65536,
                "SG_FLYWAY_RUNTIME", "实际应用观察失败；不以工具或环境错误证明迁移违规")
        document = ET.parse(observed).getroot()
        require(all(value not in observed.read_text(encoding="utf-8") for value in
                    (credentials["password"], base64.b64encode(credentials["password"].encode()).decode())),
                "SG_FLYWAY_SECRET_OUTPUT", "实际观察不能复制本轮合成凭证")
        errors = assess(document, source, credentials, output)
        native = {"started": document.get("started") == "true", "initializers": int(document.get("initializers", "0")),
                  "migration_count": len(document.findall("flyway/migration")), "row_count": len(document.findall("database/row")),
                  "columns": [dict(item.attrib) for item in document.findall("database/column")],
                  "failures": [{key: value for key, value in item.attrib.items() if key in {"flyway-code", "sqlstate"} and
                                re.fullmatch(r"[A-Z0-9_]{1,64}", value)} for item in document.findall("native-failure")]}
    calibrate_maven_source.unchanged_build(build, pathlib.Path(args.project).resolve())
    require(before == configuration() and source_sha == digest(path) and connection_sha == digest(private_path) and env == environment(args) and
            all(pathlib.Path(name).is_file() and not pathlib.Path(name).is_symlink() and digest(pathlib.Path(name)) == value for name, value in resources.items()),
            "SG_INPUT_CHANGED", "执行期间迁移输入、实际产物或工具改变")
    return {"ok": not errors, "proof_scope": "isolated-flyway-startup-sql-only", "configuration": before, "environment": env,
            "build": build, "native_observation": native, "diagnostics": errors, "startup_migrations_checked": not errors,
            "product_verified": False, "full_persistence_verified": False, "human_acceptance": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--prepare")
    operation.add_argument("--environment", action="store_true")
    for name in ("migration", "connection"):
        parser.add_argument("--" + name)
    parser.add_argument("--migration-tools", default=os.environ.get("DOMAIN_FORGE_GATE_MIGRATION_TOOLS"))
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    args = parser.parse_args()
    if args.project and not (args.migration and args.connection):
        parser.error("隔离校准须提供独立迁移合同与私有合成连接")
    try:
        result = prepare(args) if args.prepare else {"ok": True, "environment": environment(args), "diagnostics": []} if args.environment else probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.TimeoutExpired) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_FLYWAY_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
