"""对同次打包 JAR 观察回环 HTTP、失败退出与停机；只管理本次创建的进程。"""

import json
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request

from environment import require, ProbeFailure


def loopback(host):
    require(host == "127.0.0.1", "PG_RUNTIME_TARGET", "本单元已验证的监听与探测地址为 127.0.0.1")


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def request(port, path):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(f"http://127.0.0.1:{port}{path}", timeout=2) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def stopped(port):
    with socket.socket() as probe:
        return probe.connect_ex(("127.0.0.1", port)) != 0


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
            raise ProbeFailure("PG_RUNTIME_STOP", "正常终止未在声明上限内完成")


def healthy(artifact, port, log):
    command = ["java", "-jar", str(artifact), "--server.port=" + str(port)]
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=artifact.parent, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while True:
                require(process.poll() is None, "PG_RUNTIME_START", "健康就绪前进程退出：" + str(log))
                try:
                    status, body = request(port, "/actuator/health")
                    if status == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                require(time.monotonic() < deadline, "PG_RUNTIME_HEALTH", "未在声明上限取得健康响应：" + str(log))
                time.sleep(0.2)
            require(json.loads(body) == {"status": "UP"}, "PG_RUNTIME_HEALTH", "健康失败或暴露组件详情")
            hidden = {}
            for path in ("/actuator", "/actuator/env", "/actuator/configprops", "/actuator/threaddump", "/actuator/heapdump"):
                hidden[path] = request(port, path)[0]
                require(hidden[path] == 404, "PG_RUNTIME_EXPOSURE", "未声明端点可访问：" + path)
        finally:
            stop(process)
    require(stopped(port), "PG_RUNTIME_STOP", "进程退出后监听端口未释放")
    return {"command": command, "health": {"status": 200, "body": json.loads(body)}, "hidden": hidden,
            "stopped": True, "port_released": True, "exit_code": process.returncode, "log": str(log)}


def rejected(artifact, port, log, expected):
    argv = ["java", "-jar", str(artifact), "--server.port=" + str(port)]
    with log.open("w") as output:
        process = subprocess.Popen(argv, cwd=artifact.parent, stdout=output, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=45)
        except subprocess.TimeoutExpired as error:
            stop(process)
            raise ProbeFailure("PG_RUNTIME_REJECTION", "非法或占用端口没有使启动及时失败") from error
    require(code != 0 and expected.lower() in log.read_text().lower(), "PG_RUNTIME_REJECTION",
            "启动失败不属于预期端口原因：" + str(log))
    return {"command": argv, "exit_code": code, "failure_cause": expected, "log": str(log)}


def inspect(artifact, output, host="127.0.0.1"):
    loopback(host)
    first, second = free_port(), free_port()
    while second == first:
        second = free_port()
    normal = healthy(artifact, first, output / "runtime-first.log")
    override = healthy(artifact, second, output / "runtime-override.log")
    require(stopped(first), "PG_RUNTIME_PORT", "替换端口后原端口仍可连接")
    invalid = rejected(artifact, "invalid", output / "runtime-invalid.log", "server.port")
    with socket.socket() as occupied:
        occupied.bind(("127.0.0.1", 0))
        occupied.listen()
        collision = rejected(artifact, occupied.getsockname()[1], output / "runtime-occupied.log", "already in use")
    return {"ok": True, "normal": normal, "override": override, "invalid_port": invalid, "occupied_port": collision}
