"""原生 SDK 信号、实际接收及导出故障隔离校准；不提供观测平台或生产负载证明。"""

import argparse
import base64
import json
import os
import pathlib
import re
import subprocess
import tempfile

import calibrate_http
import runtime_environment as native
from calibrate_source import HERE, ProbeFailure, digest, require


def rows(signals, kind):
    return [row for batch in signals[kind] for row in batch]


def cumulative_counts(metrics):
    """累计点按单调最大值核对，多个原生导出线程到达顺序不代表采样顺序。"""
    counts = {}
    for row in metrics:
        outcome = row["attributes"]["outcome"]
        counts[outcome] = max(counts.get(outcome, 0), row["value"])
    return counts


def validate(report):
    require(report["normalResults"] == ["completed", "rejected", "unavailable"]
            and report["failureResults"] == ["completed"] * 100 and report["recoveryResult"] == "completed",
            "SG_OBSERVATION_BUSINESS", "普通遥测故障改变实际业务结果")
    for name, available in (("normalHealth", True), ("failureHealth", False), ("recoveryHealth", True)):
        require(report[name]["businessReady"] is True and report[name]["observationAvailable"] is available,
                "SG_OBSERVATION_HEALTH", "业务流量或观测状态与真实故障不一致")
    require(report["failureHealth"]["failedExports"] > 0 and report["businessMillis"] < 5000,
            "SG_OBSERVATION_ISOLATION", "没有独立故障证据或后台故障阻塞了有限请求负载")
    normal = report["normalSignals"]
    traces, logs, metrics = (rows(normal, key) for key in ("traces", "logs", "metrics"))
    require(len(traces) == 6 and len(logs) == 3 and metrics, "SG_OBSERVATION_SIGNALS", "三类信号未实际接收")
    roots = {row["span"]: row for row in traces if row["name"] == "operation"}
    children = [row for row in traces if row["name"] == "dependency"]
    require(len(roots) == 3 and len(children) == 3 and len({row["span"] for row in traces}) == 6 and
            {row["parent"] for row in children} == set(roots) and all(row["trace"] and len(row["trace"]) == 32 and
            row["trace"] != "0" * 32 for row in roots.values()) and
            all(row["parent"] in roots and row["trace"] == roots[row["parent"]]["trace"] for row in children)
            and all(row["span"] in roots and row["trace"] == roots[row["span"]]["trace"] for row in logs),
            "SG_OBSERVATION_CORRELATION", "日志、依赖调用与入口没有真实原生关联")
    counts = cumulative_counts(metrics)
    require(counts == {"completed": 1, "rejected": 1, "unavailable": 1},
            "SG_OBSERVATION_RESULTS", "真实拒绝或服务故障被计为业务成功")
    all_rows = [row for key in ("traces", "logs", "metrics") for row in rows(report["allSignals"], key)]
    require(all(row["resource"].get("service.name") == "gate-observation" and row["resource"].get("service.version") == "1.0.0"
                and row["resource"].get("project") == "alpha" and row["resource"].get("environment") == "calibration" for row in all_rows)
            and all(set(row["attributes"]) <= {"outcome"} for row in all_rows),
            "SG_OBSERVATION_DIMENSIONS", "必要资源身份缺失或任意输入进入聚合维度")
    before = report["beforeRecovery"]
    require(all(len(rows(report["allSignals"], key)) > len(rows(before, key)) for key in ("traces", "logs", "metrics")),
            "SG_OBSERVATION_RECOVERY", "恢复后没有真实新信号送达")
    recovery_trace = report["recoveryTrace"]
    recovery_roots = {row["span"] for row in rows(report["allSignals"], "traces")
                      if row["trace"] == recovery_trace and row["name"] == "operation"}
    require(len(recovery_roots) == 1 and any(row["trace"] == recovery_trace and row["span"] in recovery_roots
            for row in rows(report["allSignals"], "logs")) and any(row["trace"] == recovery_trace and
            row["parent"] in recovery_roots for row in rows(report["allSignals"], "traces")),
            "SG_OBSERVATION_RECOVERY", "恢复操作的独立新上下文未同时到达日志与父子链路")
    delivered = len(rows(report["allSignals"], "traces"))
    require(6 < delivered < 208, "SG_OBSERVATION_LOSS", "有界队列故障负载未表现真实缺失，不得伪造全部送达")
    final_counts = cumulative_counts(rows(report["allSignals"], "metrics"))
    require(final_counts == {"completed": 102, "rejected": 1, "unavailable": 1},
            "SG_OBSERVATION_RESULTS", "恢复后的实际累计指标与业务完成事实不符")


def probe(args):
    env = native.environment(args)
    root = pathlib.Path(args.fixtures)
    sources = [root / name for name in ("ObservationFixture.java", "ObservationTransport.java")]
    require(all(path.is_file() and not path.is_symlink() for path in sources), "SG_OBSERVATION_INPUT", "缺少普通被测源码")
    before = {str(path): digest(path) for path in sources}
    trusted = {name: digest(HERE / name) for name in ("ObservationInventory.java", "calibrate_observation.py", "runtime.lock.json", "runtime_environment.py")}
    classpath = os.pathsep.join(str(p) for p in sorted((pathlib.Path(args.runtime_tools) / "dependencies").glob("*.jar")))
    with tempfile.TemporaryDirectory(prefix="domain-forge-observation-") as temporary:
        output = pathlib.Path(temporary) / "observed.json"
        compilation = subprocess.run(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                                      "-d", temporary, *map(str, sources), str(HERE / "ObservationInventory.java")],
                                     text=True, capture_output=True, timeout=60)
        check_secret(compilation.stdout + compilation.stderr)
        require(compilation.returncode == 0, "SG_OBSERVATION_BUILD", "原生校准源码编译失败")
        process = subprocess.run(["java", "-cp", os.pathsep.join((temporary, classpath)), "ObservationInventory", str(output)],
                                 text=True, capture_output=True, timeout=30)
        check_secret(process.stdout + process.stderr)
        require(process.returncode == 0 and output.is_file(), "SG_OBSERVATION_RUNTIME", "原生 SDK 场景未完成")
        report = calibrate_http.read_json(output)
        check_secret(json.dumps(report))
    errors = []
    try:
        validate(report)
    except ProbeFailure as error:
        errors.append(error.diagnostic)
    require(before == {str(path): digest(path) for path in sources} and trusted == {name: digest(HERE / name) for name in trusted}
            and env == native.environment(args), "SG_INPUT_CHANGED", "实际校准对象或运行资料改变")
    return {"ok": not errors, "proof_scope": "isolated-native-sdk-http-json-calibration-only", "observed": report,
            "inputs": before, "configuration": trusted, "product_verified": False, "human_acceptance": False, "diagnostics": errors}


def check_secret(value):
    secret = "observation-calibration-secret"
    decoded = []
    for token in re.findall(r"[A-Za-z0-9+/_=-]{8,}", value):
        try:
            decoded.append(base64.b64decode(token + "=" * (-len(token) % 4), altchars=b"-_", validate=True).decode("utf-8", errors="replace"))
        except ValueError:
            continue
    require(secret not in value and all(secret not in part for part in decoded),
            "SG_OBSERVATION_SECRET", "实际日志、指标、链路或诊断泄露已知凭证")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", default=str(HERE))
    parser.add_argument("--environment", action="store_true")
    parser.add_argument("--runtime-tools", default=os.environ.get("DOMAIN_FORGE_GATE_RUNTIME_TOOLS"))
    args = parser.parse_args()
    try:
        result = {"ok": True, "environment": native.environment(args), "diagnostics": []} if args.environment else probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_OBSERVATION_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
