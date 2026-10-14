"""真实默认上下文职责及技术注解校准；不是完整架构、事务运行或产品门禁。"""

import argparse
import json
import os
import pathlib

import calibrate_architecture
import calibrate_maven_architecture
import role_boundaries
from calibrate_source import HERE, ProbeFailure, digest, require


def configuration():
    return {name: digest(HERE / name) for name in ("calibrate_roles.py", "role_boundaries.py", "role-boundaries.lock.json")}


def probe(args):
    before = configuration()
    rules = role_boundaries.baseline()
    definition = pathlib.Path(args.declaration)
    require(definition.is_file() and not definition.is_symlink(), "SG_DECLARATION", "职责声明须是可读取的普通文件")
    declared = digest(definition)
    modules = calibrate_architecture.declaration(definition, schema=3)
    report = calibrate_maven_architecture.probe(args, schema=3)
    roles, diagnostics = role_boundaries.inspect(report, modules, rules)
    require(before == configuration() and declared == digest(definition), "SG_INPUT_CHANGED", "职责校准期间输入或配置变化")
    report["diagnostics"].extend(diagnostics)
    report.update(ok=not report["diagnostics"], role_bindings=roles, role_boundaries_checked=not diagnostics,
                  role_scope="default-context-positions-calibrated-technical-references-and-declared-business-payloads-only",
                  role_configuration=before)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--environment", action="store_true")
    parser.add_argument("--declaration")
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    parser.add_argument("--tools", default=os.environ.get("DOMAIN_FORGE_GATE_TOOLS"))
    args = parser.parse_args()
    if args.project and not args.declaration:
        parser.error("--project 必须提供 --declaration")
    try:
        if args.environment:
            before = configuration()
            role_boundaries.baseline()
            _, environment = calibrate_maven_architecture.environment(args)
            require(before == configuration(), "SG_INPUT_CHANGED", "环境核对期间职责配置变化")
            result = {"ok": True, "proof_scope": "role-calibration-environment-only", "configuration": before,
                      "environment": environment, "human_acceptance": False, "diagnostics": []}
        else:
            result = probe(args)
    except ProbeFailure as failure:
        result = {"ok": False, "proof_scope": "observed-role-calibration-subset-only", "human_acceptance": False,
                  "diagnostics": [failure.diagnostic]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
