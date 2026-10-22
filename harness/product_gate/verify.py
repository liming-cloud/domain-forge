"""首个真实工程的固定质量入口；本地与 CI 使用相同执行合同。"""

import argparse
import json
import os
import re
from pathlib import Path
import sys
import tempfile

import environment
from environment import ProbeFailure, require
import advisory
import build
import runtime
import web_verify
import foundation_verify
import iam_verify

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "control_plane"))
from control_plane import input_snapshot

CHECKS = ("build-gate", "dependency-risk-gate", "behavior-scenarios", "runtime-scenarios", "product-scenarios")


def verify(args):
    root = Path(args.project).resolve()
    digest, all_inputs = input_snapshot(root)
    inputs = environment.sources(root)
    observed_environment = environment.runtime(root)
    environment.wrapper(root)
    runtime.loopback(args.host)
    workspace = Path(tempfile.mkdtemp(prefix="domain-forge-product-")).resolve()
    output = workspace / "evidence"
    output.mkdir()
    result = {"ok": False, "proof_scope": "minimal-engineering-real-product", "input": digest,
              "inputs": all_inputs, "project_inputs": inputs, "environment": observed_environment,
              "workspace": str(workspace), "checks": {}, "human_acceptance": False, "diagnostics": []}
    repository = Path(args.repository or workspace / "repository").resolve()
    cache = Path(args.wrapper_cache or workspace / "wrapper-cache").resolve()
    require(not repository.is_relative_to(root) and not cache.is_relative_to(root),
            "PG_INPUT", "依赖与 Wrapper 缓存须位于工程外")
    project = environment.copy_project(root, workspace / "project", inputs)
    try:
        argv, env, home, boot = environment.bootstrap(root, project, cache, repository, output)
        result["wrapper"] = boot
        if args.prepare_data:
            data = Path(args.prepare_data).resolve()
            require(not data.is_relative_to(root), "PG_INPUT", "原生资料须位于工程外")
            return advisory.prepare(project, data, argv, env, output)
        observed = build.lifecycle(root, project, argv, env, home, repository, output)
        result["build"] = observed
        quality = build.quality(root, project, observed, args.tools)
        result["quality"] = quality
        require(quality["ok"], "PG_SOURCE_QUALITY", "源码／架构检查失败，详见 quality.diagnostics")
        result["web"] = web_verify.inspect(root, project, observed, argv, env, home, repository, cache, workspace, output, args)
        result["foundation"] = foundation_verify.inspect(root, project, observed, result["web"], argv, env, home, repository, workspace, output, args)
        result["iam"] = iam_verify.inspect(root, project, observed, result["foundation"], argv, env, home, repository, workspace, output, args)
        result["checks"]["build-gate"] = {"ok": True, "actual_test_count": observed["tests"]["count"]}
        if args.check in ("all", "dependency-risk-gate"):
            result["advisory"] = advisory.scan(root, workspace, inputs, repository, args.advisory_data,
                                               argv, env, output, observed)
            result["checks"]["dependency-risk-gate"] = {"ok": True, "findings": len(result["advisory"]["findings"])}
        if args.check in ("all", "behavior-scenarios", "runtime-scenarios", "product-scenarios"):
            result["runtime"] = runtime.inspect(Path(observed["artifact"]["path"]), output, args.host)
            for check in ("behavior-scenarios", "runtime-scenarios", "product-scenarios"):
                result["checks"][check] = {"ok": True, "basis": "fresh source copy, real Wrapper verify and packaged JAR HTTP/lifecycle"}
        if args.check == "all":
            test_env = dict(env, DOMAIN_FORGE_PRODUCT_REPOSITORY=str(repository),
                            DOMAIN_FORGE_PRODUCT_WRAPPER_CACHE=str(cache), DOMAIN_FORGE_GATE_TOOLS=str(args.tools))
            result["counterexamples"] = environment.command(
                [sys.executable, "-B", "-m", "unittest", "discover", "-s", "harness/product_gate", "-p", "test_*.py"],
                root, output / "counterexamples.log", 600, test_env)
            test_output = (output / "counterexamples.log").read_text()
            count = re.search(r"Ran ([1-9][0-9]*) tests?\b", test_output)
            require(count and not re.search(r"skipped=[1-9]", test_output), "PG_TEST_COVERAGE", "产品反例没有完整执行")
            result["counterexamples"]["test_count"] = int(count[1])
        require(environment.sources(root) == inputs and input_snapshot(root) == (digest, all_inputs) and
                environment.runtime(root) == observed_environment, "PG_INPUT_CHANGED", "执行期间维护输入或环境变化")
        require(args.check != "all" or set(result["checks"]) == set(CHECKS), "PG_COVERAGE", "完整执行缺少必要检查")
        result["ok"] = True
    except ProbeFailure as failure:
        result["diagnostics"].append(failure.diagnostic)
    except (OSError, ValueError, KeyError) as failure:
        result["diagnostics"].append({"code": "PG_INPUT", "message": type(failure).__name__ + ": " + str(failure)})
    (output / "report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    result["report_path"] = str(output / "report.json")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=".")
    parser.add_argument("--check", choices=("all", *CHECKS), default="all")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_PRODUCT_REPOSITORY"))
    parser.add_argument("--wrapper-cache", default=os.environ.get("DOMAIN_FORGE_PRODUCT_WRAPPER_CACHE"))
    parser.add_argument("--tools", default=os.environ.get("DOMAIN_FORGE_GATE_TOOLS"))
    parser.add_argument("--http-tools", default=os.environ.get("DOMAIN_FORGE_GATE_HTTP_TOOLS"))
    parser.add_argument("--advisory-data", default=os.environ.get("DOMAIN_FORGE_GATE_ADVISORY_DATA"))
    parser.add_argument("--prepare-data", metavar="DIRECTORY")
    parser.add_argument("--environment", action="store_true")
    args = parser.parse_args()
    try:
        if args.environment:
            root = Path(args.project).resolve()
            environment.wrapper(root)
            result = {"ok": True, "proof_scope": "product-environment-only", "environment": environment.runtime(root)}
        else:
            result = verify(args)
    except ProbeFailure as failure:
        result = {"ok": False, "diagnostics": [failure.diagnostic], "human_acceptance": False}
    except (OSError, ValueError, KeyError) as failure:
        result = {"ok": False, "diagnostics": [{"code": "PG_INPUT", "message": type(failure).__name__ + ": " + str(failure)}], "human_acceptance": False}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
