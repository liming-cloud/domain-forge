"""运行装配及真实 Nacos 接入的隔离校准，容器仅限本轮私有资源。"""

import argparse
import base64
from contextlib import contextmanager
import json
import os
import pathlib
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

import calibrate_flyway
import calibrate_http
import runtime_environment as native
from calibrate_source import HERE, ProbeFailure, digest, require


def available_ports():
    """Nacos gRPC 端口固定偏移，选择同时空闲的本地端口对。"""
    for attempt in range(100):
        port = 20000 + secrets.randbelow(30000)
        with socket.socket() as http, socket.socket() as grpc:
            try:
                http.bind(("127.0.0.1", port))
                grpc.bind(("127.0.0.1", port + 1000))
            except OSError:
                continue
            return port
    raise ProbeFailure("SG_RUNTIME_ENVIRONMENT", "没有可用的隔离端口对")


@contextmanager
def nacos_server(root):
    """只启动本轮无宿主数据挂载的服务；失败也仅删除已核对归属的容器。"""
    endpoint, port = calibrate_flyway.local_endpoint(), available_ports()
    label = uuid.uuid4().hex
    password = secrets.token_hex(24)
    credentials = root / "nacos.env"
    credentials.write_text("MODE=standalone\nPREFER_HOST_MODE=hostname\nNACOS_AUTH_ENABLE=true\n"
                           "NACOS_AUTH_ADMIN_ENABLE=true\nNACOS_AUTH_CONSOLE_ENABLE=true\n"
                           "JVM_XMS=256m\nJVM_XMX=512m\nJVM_XMN=128m\nNACOS_AUTH_IDENTITY_KEY=gate\n"
                           "NACOS_AUTH_IDENTITY_VALUE=" + secrets.token_hex(24) + "\nNACOS_AUTH_TOKEN=" +
                           base64.b64encode(secrets.token_bytes(48)).decode() + "\n")
    credentials.chmod(0o600)
    container = calibrate_flyway.docker("run", "-d", "--rm", "--name", "domain-forge-runtime-" + label,
                "--label", "org.domainforge.calibration=runtime-" + label, "--tmpfs", "/home/nacos/data",
                "--tmpfs", "/home/nacos/logs", "-p", "127.0.0.1:" + str(port) + ":8848",
                "-p", "127.0.0.1:" + str(port + 1000) + ":9848", "--env-file", str(credentials),
                native.baseline()["nacos_image"], endpoint=endpoint)
    try:
        address = "http://127.0.0.1:" + str(port)
        initialized = False
        for attempt in range(90):
            request = urllib.request.Request(address + "/nacos/v3/auth/user/admin",
                                             data=urllib.parse.urlencode({"password": password}).encode())
            try:
                with urllib.request.urlopen(request, timeout=1) as response:
                    initialized = response.status == 200
                if initialized:
                    break
            except (OSError, urllib.error.URLError):
                time.sleep(0.5)
        require(initialized, "SG_RUNTIME_ENVIRONMENT", "本轮真实 Nacos 尚未完成启动与认证初始化")
        connection = root / "connection.properties"
        connection.write_text("serverAddr=127.0.0.1:" + str(port) + "\nusername=nacos\npassword=" + password +
                              "\nfirstGroup=project-alpha-test\nsecondGroup=project-beta-test\n")
        connection.chmod(0o600)
        yield connection, password, container, endpoint
    finally:
        state = json.loads(calibrate_flyway.docker("inspect", container, endpoint=endpoint))[0]
        require(state["Id"] == container and state["Config"]["Labels"].get("org.domainforge.calibration") == "runtime-" + label
                and state["Config"]["Image"] == native.baseline()["nacos_image"] and state["HostConfig"]["AutoRemove"]
                and not state["HostConfig"].get("Binds"), "SG_RUNTIME_OWNERSHIP", "容器归属改变，不清理未知资源")
        if state["State"]["Paused"]:
            calibrate_flyway.docker("unpause", container, endpoint=endpoint)
        calibrate_flyway.docker("rm", "-f", container, endpoint=endpoint)


def validate_assembly(report):
    require(set(report) == {"disabled", "default", "missing", "selected", "override", "conflict"},
            "SG_RUNTIME_COVERAGE", "装配场景未完整执行")
    require(all(row.get("unaffected") == "independent-ready" for row in report.values()),
            "SG_RUNTIME_ISOLATION", "局部配置问题影响无关能力")
    require(report["disabled"] == {"unaffected": "independent-ready", "count": 0, "state": "disabled"},
            "SG_RUNTIME_OPTIONAL", "未启用能力不应要求输入或装配")
    for name, result in (("default", "declared-target"), ("override", "override-target")):
        require(report[name] == {"unaffected": "independent-ready", "count": 1, "available": True,
                                 "result": result, "state": "available"},
                "SG_RUNTIME_OVERRIDE", "默认接入或合法覆盖未形成唯一有效实现")
    for name in ("missing", "selected"):
        require(report[name] == {"unaffected": "independent-ready", "count": 1, "available": False, "state": "unavailable"},
                "SG_RUNTIME_AVAILABILITY", "配置缺失或不支持的显式选择被隐藏或静默回退")
    require(report["conflict"] == {"unaffected": "independent-ready", "count": 2, "state": "conflict"},
            "SG_RUNTIME_CONFLICT", "多实现冲突不能靠加载顺序解决")


def validate_nacos(report):
    expected = {"initial": "alpha-1", "isolated": "beta-1", "updated": "alpha-2", "afterUpdateOther": "beta-1",
                "disconnectedFetchUnavailable": True, "cachedDuringDisconnect": "alpha-2",
                "applicationFailure": "application-unavailable", "receivedDuringFailure": "alpha-3",
                "recovered": "alpha-4", "afterRecoveryOther": "beta-1"}
    require(report == expected, "SG_RUNTIME_CONFIGURATION", "真实配置读取、生效、隔离或应用失败恢复不符合同")


def execute_probe(args, root, classes, classpath, connection=None, container=None, endpoint=None):
    output = root / "observed.json"
    argv = ["java", "-Duser.home=" + str(root), "-Dnacos.logging.path=" + str(root / "logs"),
            "-cp", os.pathsep.join((str(classes), classpath)), "RuntimeInventory", args.mode, str(output)]
    if connection:
        argv.append(str(connection))
    with (root / "runtime.log").open("w") as log:
        process = subprocess.Popen(argv, text=True, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 150
        try:
            while process.poll() is None and time.monotonic() < deadline:
                for command, marker in (("pause", "disconnect"), ("unpause", "reconnect")):
                    if container and (root / (marker + ".request")).exists() and not (root / (marker + ".ready")).exists():
                        calibrate_flyway.docker(command, container, endpoint=endpoint)
                        paused = calibrate_flyway.docker("inspect", container, "--format", "{{.State.Paused}}", endpoint=endpoint)
                        require(paused == ("true" if command == "pause" else "false"),
                                "SG_RUNTIME_FAULT", "本轮容器实际故障状态与注入要求不一致")
                        (root / (marker + ".ready")).write_text("done")
                time.sleep(0.1)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    logs = (root / "runtime.log").read_text()
    diagnostic = logs
    if connection:
        password = next(line.split("=", 1)[1] for line in connection.read_text().splitlines() if line.startswith("password="))
        diagnostic = diagnostic.replace(password, "<redacted>")
    require("actual-application-did-not-converge" not in diagnostic, "SG_RUNTIME_CONFIGURATION",
            "配置已发布但实际应用没有在声明观察期限内生效")
    require(process.returncode == 0 and output.is_file(), "SG_RUNTIME_EXECUTION", "真实运行未完成：" + diagnostic[-5000:])
    return calibrate_http.read_json(output), logs


def probe(args):
    env = native.environment(args)
    source = pathlib.Path(args.fixtures).resolve() / "RuntimeFixture.java"
    require(source.is_file() and not source.is_symlink(), "SG_RUNTIME_INPUT", "缺少普通校准源码")
    before = digest(source)
    trusted = {name: digest(HERE / name) for name in ("RuntimeInventory.java", "runtime_environment.py", "calibrate_runtime.py", "runtime.lock.json")}
    classpath = os.pathsep.join(str(p) for p in sorted((pathlib.Path(args.runtime_tools) / "dependencies").glob("*.jar")))
    with tempfile.TemporaryDirectory(prefix="domain-forge-runtime-") as temporary:
        root = pathlib.Path(temporary)
        classes = root / "classes"
        classes.mkdir()
        compiled = subprocess.run(["javac", "--release", "21", "-parameters", "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                                   "-d", str(classes), str(source), str(HERE / "RuntimeInventory.java")],
                                  text=True, capture_output=True, timeout=60)
        require(compiled.returncode == 0, "SG_RUNTIME_BUILD", compiled.stderr)
        if args.mode == "nacos":
            with nacos_server(root) as (connection, password, container, endpoint):
                observed, logs = execute_probe(args, root, classes, classpath, connection, container, endpoint)
                require(password not in logs + json.dumps(observed), "SG_RUNTIME_SECRET", "运行诊断泄露连接凭证")
        else:
            observed, logs = execute_probe(args, root, classes, classpath)
    errors = []
    try:
        (validate_assembly if args.mode == "assembly" else validate_nacos)(observed)
    except ProbeFailure as error:
        errors.append(error.diagnostic)
    require(before == digest(source) and trusted == {name: digest(HERE / name) for name in trusted}
            and env == native.environment(args), "SG_INPUT_CHANGED", "执行期间实际输入或依赖改变")
    return {"ok": not errors, "proof_scope": "isolated-boot-runtime-" + args.mode, "observed": observed,
            "input": before, "configuration": trusted, "product_verified": False, "human_acceptance": False, "diagnostics": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare")
    parser.add_argument("--environment", action="store_true")
    parser.add_argument("--mode", choices=("assembly", "nacos"), default="assembly")
    parser.add_argument("--fixtures", default=str(HERE))
    parser.add_argument("--runtime-tools", default=os.environ.get("DOMAIN_FORGE_GATE_RUNTIME_TOOLS"))
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    args = parser.parse_args()
    try:
        result = native.prepare(args) if args.prepare else {"ok": True, "environment": native.environment(args), "diagnostics": []} if args.environment else probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_RUNTIME_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
