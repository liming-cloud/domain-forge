"""在同次真实构建上组合源码、合同和职责检查；语义选择与动态行为仍须相应证据。"""

import argparse
import json
import os
import pathlib

import calibrate_maven_architecture
import calibrate_maven_source
import calibrate_roles
from calibrate_source import HERE, ProbeFailure, digest, require


def probe(args):
    configuration = {name: digest(HERE / name) for name in ("calibrate_engineering.py", *calibrate_maven_source.configuration())}
    report = calibrate_roles.probe(args)
    paths, environment = calibrate_maven_source.environment(args)
    groups, implementations, errors = calibrate_maven_source.source_checks(args, report["build"], paths)
    report["diagnostics"].extend(errors)
    calibrate_maven_source.unchanged_build(report["build"], pathlib.Path(args.project).resolve())
    require(configuration == {name: digest(HERE / name) for name in configuration}
            and environment == calibrate_maven_source.environment(args)[1], "SG_INPUT_CHANGED", "组合源码检查期间输入变化")
    report.update(ok=not report["diagnostics"], proof_scope="same-build-source-contract-role-mechanisms-only",
                  compilation_checks=groups, implementation_inventory=implementations, combined_configuration=configuration,
                  semantic_obligations=["公开合同与关键内部方法登记完整性", "数据合同选择与职责语义", "说明准确性与合法省略",
                                        "实际框架入口与运行装配由对应场景核对"])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project")
    parser.add_argument("--declaration")
    parser.add_argument("--environment", action="store_true")
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    parser.add_argument("--tools", default=os.environ.get("DOMAIN_FORGE_GATE_TOOLS"))
    args = parser.parse_args()
    if not args.environment and not (args.project and args.declaration):
        parser.error("需要实际隔离工程与声明")
    try:
        if args.environment:
            _, environment = calibrate_maven_architecture.environment(args)
            result = {"ok": True, "environment": environment, "diagnostics": []}
        else:
            result = probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
