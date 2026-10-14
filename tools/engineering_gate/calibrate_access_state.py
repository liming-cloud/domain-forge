"""真实 Redis、跨进程访问状态与安全写入的固定第五层校准。"""

import argparse
import base64
import http.client
import json
import os
import pathlib
import re
import subprocess
import tempfile
import time
import urllib.parse
import xml.etree.ElementTree as ET

import access_state_environment as native
import calibrate_access
import calibrate_flyway
import calibrate_maven
import calibrate_maven_source
from calibrate_source import HERE, ProbeFailure, digest, execute, require


PROFILES = {"session", "expiration", "authorization", "changes", "password", "failures", "throttle", "audit"}
PASSWORD = "Initial-gate-password!7"
CHANGED = "Changed-gate-password!8"
ROUTES = {("POST", "/gate/" + name) for name in ("login", "logout", "password", "admin/state", "admin/reconcile")} | {
    ("GET", "/gate/identity"), ("GET", "/gate/resources"), ("GET", "/gate/resource/{id}"), ("POST", "/gate/resource/{id}")}


def configuration():
    return {name: digest(HERE / name) for name in ("calibrate_access_state.py", "access_state_environment.py", "access_state_scenarios.py",
            "access-state.lock.json", "AccessStateInventory.java", "sa-access.lock.json", "http-contract.lock.json", "password.lock.json", "flyway.lock.json")}


def declaration(path):
    source = calibrate_flyway.read_json(path)
    require(set(source) == {"schema", "application", "profile"} and type(source["schema"]) is int and source["schema"] == 1 and
            isinstance(source["profile"], str) and source["profile"] in PROFILES and isinstance(source["application"], str) and
            re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", source["application"]),
            "SG_ACCESS_STATE_INPUT", "固定校准声明不得自填期望、跳过场景或增加任意 SQL")
    return source


class Runtime:
    """本轮独立进程及私有观察；公开报告只保留固定场景和布尔结果。"""

    def __init__(self, args, source, credentials, endpoint, compilation, private):
        self.args, self.source, self.credentials, self.endpoint = args, source, credentials, endpoint
        self.compilation, self.private = compilation, private
        self.nodes, self.interactions, self.material = {}, [], [PASSWORD, CHANGED, credentials["postgres"]["password"], credentials["redis"]["password"]]
        self.responses = []
        self.classes = private / "observer"
        self.classes.mkdir()
        self.classpath = os.pathsep.join(compilation["classpath"])
        result = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", self.classpath,
                          "-d", str(self.classes), str(HERE / "AccessStateInventory.java")])
        require(result.returncode == 0, "SG_ACCESS_STATE_TOOL", "原生观察器编译失败，不是访问规则反例")

    def sql(self, query):
        return calibrate_flyway.docker("exec", self.credentials["postgres"]["container"], "psql", "-h", "127.0.0.1", "-U", "gate", "-d", "gate", "-At", "-v", "ON_ERROR_STOP=1", "-c", query, endpoint=self.endpoint)

    def redis(self, *arguments, database=0):
        return calibrate_flyway.docker("exec", self.credentials["redis"]["container"], "redis-cli", "--no-auth-warning", "--raw", "-n", str(database), *map(str, arguments), endpoint=self.endpoint)

    def start(self, name, project="alpha", database=0, **properties):
        env = dict(os.environ)
        pg, redis = self.credentials["postgres"], self.credentials["redis"]
        env.update(DF_GATE_JDBC_URL=pg["jdbc_url"], DF_GATE_DB_USER=pg["username"], DF_GATE_DB_PASSWORD=pg["password"],
                   DF_GATE_REDIS_PORT=str(redis["port"]), DF_GATE_REDIS_PASSWORD=redis["password"], DF_GATE_REDIS_DATABASE=str(database),
                   DF_GATE_PROJECT=project, DF_GATE_TOKEN_TIMEOUT="1" if self.source["profile"] == "expiration" else "120")
        env.update(properties)
        observed, log = self.private / (name + ".xml"), self.private / (name + ".log")
        observed.unlink(missing_ok=True)
        output = log.open("ab")
        process = subprocess.Popen(["java", "-XX:ActiveProcessorCount=2", "-cp", os.pathsep.join([str(self.classes), self.classpath]),
                                    "AccessStateInventory", self.source["application"], str(observed)], stdout=output, stderr=subprocess.STDOUT, env=env)
        self.nodes[name] = {"process": process, "output": output, "project": project, "database": database, "log": log}
        deadline = time.monotonic() + 12
        while process.poll() is None and time.monotonic() < deadline:
            if observed.exists():
                try:
                    document = ET.parse(observed).getroot()
                    break
                except ET.ParseError:
                    pass
            time.sleep(0.05)
        else:
            require(False, "SG_ACCESS_STATE_START", "实际应用启动未完成；不以工具或依赖失败证明规则违规")
        self.assess_node(document, process, project, database)
        self.nodes[name]["port"] = int(document.get("port"))
        self.nodes[name]["pid"] = process.pid

    def assess_node(self, document, process, project, database):
        output = pathlib.Path(self.compilation["output"]).resolve()
        required = {"pid": str(process.pid), "project": project,
                    "jdbc-url": self.credentials["postgres"]["jdbc_url"], "database-version": calibrate_flyway.baseline()["postgresql_version"],
                    "driver-version": calibrate_flyway.baseline()["driver_version"], "dao": "cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate",
                    "dao-bound": "true", "redis-host": "127.0.0.1", "redis-port": str(self.credentials["redis"]["port"]),
                    "redis-database": str(database), "token-name": "Authorization", "token-prefix": "Bearer",
                    "read-header": "true", "read-body": "false", "read-cookie": "false"}
        mismatched = [key for key, value in required.items() if document.get(key) != value]
        require(not mismatched, "SG_ACCESS_STATE_RESOURCE", "实际资源不符：" + ",".join(mismatched))
        version = calibrate_access.baseline()["sa_token_version"]
        dao = next(path for path in self.compilation["classpath"] if pathlib.Path(path).name == "sa-token-redis-template-" + version + ".jar")
        require(self.location(document.get("dao-source")) == pathlib.Path(dao).resolve() and
                self.location(document.get("application-source")) == output,
                "SG_ACCESS_STATE_RESOURCE", "入口和原生 DAO 必须来自实际构建及依赖，不能由同名类代替")
        routes = document.findall("route")
        require(len(routes) == len(ROUTES) and {(r.get("method"), r.get("path")) for r in routes} == ROUTES and
                all(self.location(r.get("source")) == output for r in routes), "SG_ACCESS_STATE_COVERAGE", "固定合同必须覆盖同次编译实际注册入口")

    @staticmethod
    def location(value):
        uri = urllib.parse.urlsplit(value or "")
        require(uri.scheme == "file" and not uri.netloc, "SG_ACCESS_STATE_RESOURCE", "实际类须有本地物理来源")
        return pathlib.Path(urllib.parse.unquote(uri.path)).resolve()

    def stop(self, name):
        node = self.nodes[name]
        process = node["process"]
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)
        node["output"].close()

    def close(self):
        for name in self.nodes:
            self.stop(name)

    def output(self):
        return "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in self.private.glob("*.log"))

    def request(self, name, method, path, expected=200, token=None, body=None, scenario=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        client = http.client.HTTPConnection("127.0.0.1", self.nodes[name]["port"], timeout=4)
        try:
            client.request(method, path, json.dumps(body) if body is not None else None, headers)
            response = client.getresponse()
            content = response.read(65537)
            require(len(content) <= 65536, "SG_ACCESS_STATE_HTTP", "合成响应超出固定边界")
            result = json.loads(content)
            self.interactions.append({"scenario": scenario or path, "node": name, "status": response.status})
            accepted = (expected,) if isinstance(expected, int) else expected
            require(response.status in accepted, "SG_ACCESS_STATE_BEHAVIOR", "实际成功、拒绝或失败状态不符合固定合同")
            code = {200: "SUCCESS", 401: "UNAUTHENTICATED", 403: "FORBIDDEN", 429: "LIMITED", 503: "DEPENDENCY"}[response.status]
            require(isinstance(result, dict) and set(result) == {"code", "message", "data"} and result["code"] == code,
                    "SG_ACCESS_STATE_HTTP", "实际响应外壳或失败分类错误")
            require(response.status == 200 or result["data"] is None, "SG_ACCESS_STATE_SECRET", "失败响应不得复制内部数据或凭证")
            visible = dict(result)
            if path == "/gate/login" and response.status == 200 and isinstance(result["data"], dict):
                visible["data"] = {key: value for key, value in result["data"].items() if key != "token"}
            public = json.dumps({"headers": response.getheaders(), "body": visible}, ensure_ascii=False)
            self.responses.append(public)
            require(all(value not in public and base64.b64encode(value.encode()).decode() not in public for value in self.material if value),
                    "SG_ACCESS_STATE_SECRET", "令牌交付字段以外的响应不得复制本轮密码、令牌或摘要")
            return result["data"]
        finally:
            client.close()

    def login(self, name, identifier=2, password=PASSWORD, expected=200):
        self.material.append(password)
        data = self.request(name, "POST", "/gate/login", expected, body={"id": identifier, "password": password})
        if expected == 200:
            require(isinstance(data, dict) and set(data) == {"token"} and isinstance(data["token"], str) and len(data["token"]) >= 16,
                    "SG_ACCESS_STATE_SESSION", "登录须取得实际原生令牌")
            self.material.append(data["token"])
            return data["token"]
        return None

    def check_output(self):
        hashes = self.sql("SELECT hash FROM gate_account ORDER BY project,id;").splitlines()
        values = [*self.material, *hashes]
        audit = self.sql("SELECT row_to_json(a)::text FROM gate_audit a ORDER BY event;")
        observed = self.output() + "\n" + audit + "\n" + "\n".join(self.responses)
        require(all(value not in observed and base64.b64encode(value.encode()).decode() not in observed for value in values if value),
                "SG_ACCESS_STATE_SECRET", "本轮合成密码、令牌或新旧摘要进入响应、进程输出或审计")


def probe(args):
    before, env = configuration(), native.environment(args)
    source = declaration(pathlib.Path(args.state))
    declared = digest(pathlib.Path(args.state))
    credentials, endpoint = native.connections(pathlib.Path(args.connections))
    credentials_sha = digest(pathlib.Path(args.connections))
    build = calibrate_maven.probe(args)
    require(build["ok"], "SG_ACCESS_STATE_BUILD", "实际 Maven 编译须成功")
    compilation = next(c for c in build["compilations"] if c["purpose"] == "production")
    require((pathlib.Path(compilation["output"]) / (source["application"].replace(".", "/") + ".class")).is_file(),
            "SG_ACCESS_STATE_ENTRY", "应用入口必须来自同次生产编译")
    versions = {(d["group"], d["artifact"]): d["version"] for d in compilation["dependencies"]}
    require(all(versions.get(key) == value for key, value in native.required_versions().items()) and
            all(value == calibrate_access.baseline()["sa_token_version"] for (group, _), value in versions.items() if group == "cn.dev33"),
            "SG_ACCESS_STATE_CALIBRATION", "实际应用组合未校准，不能用工具目录补齐依赖")
    with tempfile.TemporaryDirectory(prefix="domain-forge-access-state-private-") as directory:
        runtime = Runtime(args, source, credentials, endpoint, compilation, pathlib.Path(directory))
        try:
            import access_state_scenarios
            runtime.start("a")
            runtime.start("b")
            runtime.start("c", "beta", 1)
            access_state_scenarios.run(runtime, source["profile"])
        finally:
            runtime.close()
        runtime.check_output()
        interactions = runtime.interactions
        pids = [node["pid"] for node in runtime.nodes.values()]
        require(len(set(pids)) == 3, "SG_ACCESS_STATE_RESOURCE", "必须使用三个独立真实应用进程")
    calibrate_maven_source.unchanged_build(build, pathlib.Path(args.project).resolve())
    require(before == configuration() and env == native.environment(args) and declared == digest(pathlib.Path(args.state)) and
            credentials_sha == digest(pathlib.Path(args.connections)), "SG_INPUT_CHANGED", "本轮规则、构建、环境或连接输入发生改变")
    return {"ok": True, "proof_scope": "isolated-access-state-profile-only", "profile": source["profile"], "build": build,
            "configuration": before, "environment": env, "interactions": interactions, "diagnostics": [],
            "real_redis_checked": True, "processes": 3, "product_verified": False, "access_policy_verified": False,
            "semantic_review_proven": False, "human_acceptance": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--prepare")
    operation.add_argument("--environment", action="store_true")
    for name in ("state", "connections"):
        parser.add_argument("--" + name)
    parser.add_argument("--access-state-tools", default=os.environ.get("DOMAIN_FORGE_GATE_ACCESS_STATE_TOOLS"))
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    args = parser.parse_args()
    if args.project and not (args.state and args.connections):
        parser.error("隔离应用须提供固定场景合同与本轮私有连接")
    try:
        report = native.prepare(args) if args.prepare else {"ok": True, "environment": native.environment(args), "diagnostics": []} if args.environment else probe(args)
    except ProbeFailure as error:
        report = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, http.client.HTTPException) as error:
        report = {"ok": False, "diagnostics": [{"code": "SG_ACCESS_STATE_INPUT", "message": type(error).__name__}]}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
