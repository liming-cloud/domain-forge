"""真实 Boot HTTP 消费与结果计量校准；不提供可发布客户端或产品验收。"""

import argparse
import base64
from collections import Counter
import json
import os
import pathlib
import re
import subprocess
import tempfile

import calibrate_http
from calibrate_source import HERE, ProbeFailure, digest, require


FIXTURES = ("HttpConsumerFixture.java",)
OBSERVERS = ("HttpConsumerInventory.java",)
EXPECTED = {"success": "completed", "empty": "completed", "rejected": "rejected", "accepted": "pending",
            "partial": "partial", "protocol": "protocol-error", "unauthenticated": "unauthenticated",
            "forbidden": "forbidden", "unavailable": "unavailable", "invalid": "unknown", "unknown": "unknown",
            "timeout": "unknown", "redirect": "unknown", "download": "completed", "stream": "completed", "operations": "completed"}


def validate(observed):
    """独立合同同时核对消费分类、未完成信息、真实副作用和结果计量。"""
    require(set(observed) == {"results", "outcomes", "effects", "wire"} and set(observed["results"]) == set(EXPECTED)
            and len(observed["wire"]) == len(EXPECTED),
            "SG_HTTP_CONSUMER_COVERAGE", "真实消费场景不完整")
    results = observed["results"]
    require(all(item["outcome"] == EXPECTED[key] for key, item in results.items()),
            "SG_HTTP_CONSUMER_RESULT", "消费方误判完成、拒绝或未知结果")
    statuses = {"protocol": 400, "unauthenticated": 401, "forbidden": 403, "unavailable": 503, "timeout": 0, "redirect": 302}
    require(all(item["status"] == statuses.get(key, 200) for key, item in results.items()),
            "SG_HTTP_CONSUMER_STATUS", "响应解析失败不得丢失已收到的 HTTP 状态")
    require(all(results[key]["data"] == {"statusUrl": "/operations/42"} for key in ("accepted", "partial")),
            "SG_HTTP_CONSUMER_STATE", "消费方丢失状态查询信息")
    require(observed["effects"] == 1, "SG_HTTP_CONSUMER_RETRY", "超时不能推断未发生并自动重复操作")
    require(observed["outcomes"] == dict(Counter(EXPECTED.values())),
            "SG_HTTP_CONSUMER_METRICS", "结果计量必须识别业务拒绝及未知结果，不能只计 HTTP 成功")
    require(results["download"]["data"] == "AB" and results["stream"]["data"] == "data: first\n\ndata: last\n\n"
            and json.loads(results["operations"]["data"]) == {"status": "UP"},
            "SG_HTTP_CONSUMER_PROTOCOL", "消费方破坏下载、流式或运维协议")


def probe(args):
    env = calibrate_http.environment(args)
    root = pathlib.Path(args.fixtures).resolve()
    require(root.is_dir() and not any((root / name).is_symlink() for name in FIXTURES),
            "SG_HTTP_CONSUMER_INPUT", "校准源码必须为普通文件")
    before = {name: digest(root / name) for name in FIXTURES}
    trusted = {name: digest(HERE / name) for name in (*OBSERVERS, "calibrate_http_consumer.py")}
    tools = pathlib.Path(args.http_tools) / "framework/dependencies"
    classpath = os.pathsep.join(str(path) for path in sorted(tools.glob("*.jar")))
    with tempfile.TemporaryDirectory(prefix="domain-forge-http-consumer-") as temporary:
        output = pathlib.Path(temporary) / "observation.json"
        command = ["javac", "--release", "21", "-parameters", "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                   "-d", temporary, *[str(root / name) for name in FIXTURES], *[str(HERE / name) for name in OBSERVERS]]
        compiled = subprocess.run(command, text=True, capture_output=True, timeout=60)
        check_secret(compiled.stdout + compiled.stderr)
        require(compiled.returncode == 0, "SG_HTTP_CONSUMER_BUILD", "真实消费源码编译失败")
        run = subprocess.run(["java", "-cp", os.pathsep.join((temporary, classpath)), "HttpConsumerInventory", str(output)],
                             text=True, capture_output=True, timeout=60)
        check_secret(run.stdout + run.stderr)
        require(run.returncode == 0 and output.is_file(), "SG_HTTP_CONSUMER_RUNTIME", "真实消费场景未完成")
        observed = calibrate_http.read_json(output)
        check_secret(json.dumps(observed))
    validate(observed)
    require(before == {name: digest(root / name) for name in FIXTURES}
            and trusted == {name: digest(HERE / name) for name in trusted} and env == calibrate_http.environment(args),
            "SG_INPUT_CHANGED", "执行期间源码或工具发生变化")
    return {"ok": True, "proof_scope": "isolated-http-consumer-and-outcome-counting-only", "observed": observed,
            "inputs": before, "configuration": trusted, "product_verified": False, "human_acceptance": False, "diagnostics": []}


def check_secret(value):
    secret = "consumer-calibration-secret"
    decoded = []
    for token in re.findall(r"[A-Za-z0-9+/_=-]{8,}", value):
        try:
            decoded.append(base64.b64decode(token + "=" * (-len(token) % 4), altchars=b"-_", validate=True).decode("utf-8", errors="replace"))
        except ValueError:
            continue
    require(secret not in value and all(secret not in part for part in decoded),
            "SG_HTTP_CONSUMER_SECRET", "实际日志、指标、链路或诊断泄露已知凭证")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", default=str(HERE))
    parser.add_argument("--environment", action="store_true")
    parser.add_argument("--http-tools", default=os.environ.get("DOMAIN_FORGE_GATE_HTTP_TOOLS"))
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    args = parser.parse_args()
    try:
        result = calibrate_http.environment(args) if args.environment else probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_HTTP_CONSUMER_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
