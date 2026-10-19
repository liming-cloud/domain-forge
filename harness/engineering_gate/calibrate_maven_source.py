"""连接真实 Maven 编译范围与源码检查；完整源码门禁仍须合同及语义证据。"""

import argparse
import json
import os
import pathlib
import sys
from types import SimpleNamespace
import xml.etree.ElementTree as ET

import calibrate_maven
import calibrate_source
from calibrate_source import HERE, ProbeFailure, digest, require


def configuration():
    """固定组合入口与实际使用的配置，不接受调用方缩小必要检查。"""
    names = ("calibrate_maven_source.py", "calibrate_maven.py", "MavenScopeSpy.java", "maven-scope.lock.json",
             "calibrate_source.py", "MetricInventory.java", "tools.lock.json", "comments.xml", "methods.xml",
             "measure.xml", "complexity.json")
    return {name: digest(HERE / name) for name in names}


def environment(args):
    calibrate_source.complexity_baseline()
    paths, source = calibrate_source.toolchain(args.tools)
    maven = calibrate_maven.environment(args)["environment"]
    return paths, {"source": source, "maven": maven}


def unchanged_build(build, project):
    """源码检查不能借用已漂移的维护输入或类产物；不是抗伪造凭证。"""
    require(build["inputs"] == calibrate_maven.project_inputs(project),
            "SG_INPUT_CHANGED", "编译后维护输入变化，源码与构建证据不再对应")
    actual = set()
    for compilation in build["compilations"]:
        output = pathlib.Path(compilation["output"])
        require(not any(path.is_symlink() for path in output.rglob("*")),
                "SG_INPUT_CHANGED", "源码检查期间类产物出现符号链接")
        actual.update(str(path.resolve()) for path in output.rglob("*.class"))
    calibrate_maven.exact_coverage(set(build["artifacts"]), actual, "SG_INPUT_CHANGED", "源码检查期间类产物范围变化")
    require(build["artifacts"] == {path: digest(pathlib.Path(path)) for path in actual},
            "SG_INPUT_CHANGED", "源码检查期间类产物变化")
    outputs = {scope["output"] for scope in build["compilations"]}
    dependencies = {path for item in build["compilations"] for path in item["classpath"] if path not in outputs}
    require(set(build["classpath_artifacts"]) == dependencies and
            all(pathlib.Path(path).is_file() and not pathlib.Path(path).is_symlink() and digest(pathlib.Path(path)) == checksum
                for path, checksum in build["classpath_artifacts"].items()),
            "SG_INPUT_CHANGED", "源码或架构检查期间实际依赖对象缺失、范围或内容变化")


def source_checks(args, build, paths):
    """所有实际源码执行类型及格式检查，实际实现执行对应用途的数值基线。"""
    all_sources = [path for compilation in build["compilations"] for path in compilation["sources"]]
    implementations = calibrate_source.metric_inventory(paths, list(map(pathlib.Path, all_sources)))
    implemented_files = {item["file"] for item in implementations}
    groups, diagnostics = [], []
    for compilation in build["compilations"]:
        purpose = {"compile": "production", "testCompile": "test"}[compilation["goal"]]
        require(purpose == compilation["purpose"], "SG_SOURCE_BINDING", "真实编译用途与检查范围不一致")
        sources = compilation["sources"]
        measured_sources = [path for path in sources if path in implemented_files]
        checks = []
        for action in ("comments", "format", "complexity"):
            selected = measured_sources if action == "complexity" else sources
            if selected:
                result = calibrate_source.probe(SimpleNamespace(action=action, tools=args.tools, purpose=purpose, sources=selected))
                require(result["inputs"] == {path: build["inputs"][path] for path in selected} and
                        set(result["scanned"]) == set(selected), "SG_SOURCE_BINDING", "实际源码检查与编译输入不一致")
            else:
                result = {"ok": True, "action": action, "scanned": [], "inputs": {}, "diagnostics": [],
                          "proof_scope": "no-explicit-implementations-in-observed-scope"}
            checks.append(result)
            diagnostics.extend(dict(item, purpose=purpose, goal=compilation["goal"], execution=compilation["execution"])
                               for item in result["diagnostics"])
        groups.append({"purpose": purpose, "goal": compilation["goal"], "execution": compilation["execution"],
                       "sources": sources, "without_explicit_implementations": [path for path in sources if path not in implemented_files],
                       "checks": checks})
    return groups, implementations, diagnostics


def probe(args):
    before_configuration = configuration()
    paths, before_environment = environment(args)
    build = calibrate_maven.probe(args)
    project = pathlib.Path(args.project).resolve()
    unchanged_build(build, project)
    groups, implementations, diagnostics = source_checks(args, build, paths)
    unchanged_build(build, project)
    require(before_configuration == configuration(), "SG_INPUT_CHANGED", "联合检查期间适配器或配置变化")
    _, after_environment = environment(args)
    require(before_environment == after_environment, "SG_INPUT_CHANGED", "联合检查期间工具或运行环境变化")
    return {"ok": not diagnostics, "proof_scope": "observed-maven-source-subset-only", "build_purpose_bound": True,
            "source_gate_proven": False, "module_ownership_proven": False, "contract_selection_proven": False,
            "semantic_review_proven": False, "human_acceptance": False, "publication_proven": False,
            "build": build, "compilation_checks": groups, "implementation_inventory": implementations,
            "configuration": before_configuration, "environment": before_environment, "diagnostics": diagnostics}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--environment", action="store_true")
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    parser.add_argument("--tools", default=os.environ.get("DOMAIN_FORGE_GATE_TOOLS"))
    args = parser.parse_args()
    try:
        if args.environment:
            _, observed = environment(args)
            result = {"ok": True, "proof_scope": "maven-source-environment-only", "environment": observed, "diagnostics": []}
        else:
            result = probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, ET.ParseError) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_BUILD_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
