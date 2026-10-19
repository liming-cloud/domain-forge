"""将真实 Maven 编译范围连接逻辑模块和精确合同；不是完整工程门禁。"""

import argparse
import json
import os
import pathlib
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

import calibrate_architecture
import calibrate_maven
import calibrate_maven_source
import calibrate_source
import dependency_declarations
import method_contracts
from calibrate_source import HERE, ProbeFailure, digest, require


def configuration():
    names = ("calibrate_maven_architecture.py", "calibrate_architecture.py", "ArchitectureInventory.java",
             "calibrate_maven.py", "MavenScopeSpy.java", "maven-scope.lock.json", "calibrate_maven_source.py",
             "calibrate_source.py", "MetricInventory.java", "tools.lock.json", "method_contracts.py", "MethodInventory.java", "comments.xml",
             "dependency_declarations.py", "calibrate_lifecycle.py", "calibrate_wrapper.py", "maven-lifecycle.lock.json")
    return {name: digest(HERE / name) for name in names}


def environment(args):
    paths, source = calibrate_source.toolchain(args.tools)
    return paths, {"source": source, "maven": calibrate_maven.environment(args)["environment"]}


def bind_types(build, types, paths, modules):
    """真实产物来源及语法树包声明共同绑定源码，不依赖文件布局或可见性。"""
    by_artifact = {path: item for item in build["compilations"] for path in item["classes"]}
    files = calibrate_source.syntax_inventory(paths, [pathlib.Path(path) for item in build["compilations"] for path in item["sources"]])
    sources = {file.attrib["name"]: file.attrib["package"] for file in files}
    bindings, assigned, imported = [], set(), set()
    for item in types:
        uri = urllib.parse.urlsplit(item["source_uri"])
        require(uri.scheme == "file" and not (uri.netloc or uri.query or uri.fragment),
                "SG_SOURCE_BINDING", "类产物缺少可核对的实际文件来源：" + item["name"])
        artifact = str(pathlib.Path(urllib.request.url2pathname(uri.path)))
        require(artifact in by_artifact and artifact not in imported,
                "SG_SOURCE_BINDING", "导入对象不属于唯一实际编译产物：" + item["name"])
        compilation = by_artifact[artifact]
        expected_name = pathlib.Path(artifact).relative_to(compilation["output"]).with_suffix("").as_posix().replace("/", ".")
        require(expected_name == item["name"], "SG_SOURCE_BINDING", "类型与实际类产物不匹配：" + artifact)
        candidates = [path for path in compilation["sources"]
                      if pathlib.Path(path).name == item["source_name"] and sources[path] == item["package"]]
        require(len(candidates) == 1, "SG_SOURCE_BINDING", "无法唯一关联实际源码：" + item["name"])
        matches = [identifier for identifier, module in modules.items() if calibrate_architecture.inside(item["name"], module["package"])]
        purpose = {"compile": "production", "testCompile": "test"}[compilation["goal"]]
        require(purpose == compilation["purpose"], "SG_SOURCE_BINDING", "实际编译用途不一致")
        binding = {"name": item["name"], "module": matches[0] if len(matches) == 1 else None,
                   "source": candidates[0], "bytecode": artifact, "purpose": purpose,
                   "goal": compilation["goal"], "execution": compilation["execution"]}
        item.update(purpose=purpose)
        bindings.append(binding)
        assigned.add(candidates[0])
        imported.add(artifact)
    calibrate_maven.exact_coverage(set(by_artifact), imported, "SG_SOURCE_BINDING", "实际类产物未完全关联")
    calibrate_maven.exact_coverage(set(sources), assigned, "SG_SOURCE_BINDING", "实际源码未完全关联到类型产物；无类型文件尚未校准")
    return bindings


def select_contracts(modules, types, bindings):
    """选取实际存在的生产合同对象，返回精确签名与来源供后续说明检查。"""
    by_name = {item["name"]: item for item in types}
    by_binding = {item["name"]: item for item in bindings}
    contracts = [dict(by_binding[name], members=sorted([member for member in by_name[name]["members"]
                                                  if member["id"] in module["contract_members"] and member["public"] == "true"],
                                                 key=lambda member: member["id"]))
            for module in modules.values() for name in module["contract_types"]
            if name in by_name and by_binding[name]["module"] == module["id"]
            and by_name[name]["public"] == "true" and by_binding[name]["purpose"] == "production"]
    selected = {member["id"] for contract in contracts for member in contract["members"]}
    for module in modules.values():
        for identifier in module.get("critical_methods", []):
            matches = [(name, member) for name, item in by_name.items() for member in item["members"]
                       if member["id"] == identifier and "(" in identifier and by_binding[name]["module"] == module["id"]
                       and by_binding[name]["purpose"] == "production"]
            require(len(matches) == 1, "SG_CRITICAL_METHOD", "关键方法须绑定同模块真实生产声明：" + identifier)
            if identifier not in selected:
                name, member = matches[0]
                contracts.append(dict(by_binding[name], members=[member], critical=True))
                selected.add(identifier)
    return contracts


def origin_violations(build, types):
    """真实类型和继承声明者只可来自本次产物、已观察依赖或 JDK。"""
    allowed = set(build["artifacts"]) | set(build["classpath_artifacts"])
    errors = []

    def inspect(owner, reference, origin, kind, prefix=""):
        if reference.get(prefix + "primitive") == "true":
            return
        target = reference.get("target") if not prefix else reference["resolved_owner"]
        detail = {"type": owner["name"], "origin": origin, "target": target, "reference_kind": kind,
                  "source_uri": reference.get(prefix + "source_uri", ""),
                  "origin_uri": reference.get(prefix + "origin_uri", "")}
        if reference.get(prefix + "fully_imported") != "true":
            errors.append(dict(code="SG_UNRESOLVED_TYPE", **detail))
            return
        uri = urllib.parse.urlsplit(detail["origin_uri"])
        if uri.scheme == "jrt" and not (uri.netloc or uri.query or uri.fragment):
            return
        if uri.scheme == "file" and not (uri.netloc or uri.query or uri.fragment):
            path = str(pathlib.Path(urllib.request.url2pathname(uri.path)))
            if path in allowed:
                return
        errors.append(dict(code="SG_DEPENDENCY_ORIGIN", **detail))

    for owner in types:
        for reference in owner["dependencies"]:
            inspect(owner, reference, owner["name"], "dependency")
        for member in owner["members"]:
            for reference in member["signature_types"]:
                inspect(owner, reference, member["id"], "signature")
        for reference in owner["shape_types"]:
            inspect(owner, reference, reference["origin"], "payload-shape")
        for access in owner["accesses"]:
            if access["resolved"]:
                inspect(owner, access, access["origin"], "resolved-owner", "resolved_")
    return errors


def probe(args, schema=2):
    before_configuration = configuration()
    definition = pathlib.Path(args.declaration)
    require(definition.is_file() and not definition.is_symlink(), "SG_DECLARATION", "模块声明缺失或为符号链接")
    declaration_hash = digest(definition)
    modules = calibrate_architecture.declaration(definition, schema=schema)
    require(declaration_hash == digest(definition), "SG_INPUT_CHANGED", "读取期间模块声明变化")
    capability_rules = dependency_declarations.capabilities()
    paths, before_environment = environment(args)
    build = calibrate_maven.probe(args)
    project = pathlib.Path(args.project).resolve()
    calibrate_maven_source.unchanged_build(build, project)
    roots, inputs, expected = calibrate_architecture.bytecode_inputs([item["output"] for item in build["compilations"]])
    require(inputs == build["artifacts"], "SG_SOURCE_BINDING", "实际构建类产物与导入输入不一致")
    types = calibrate_architecture.inventory(paths, roots, expected, list(build["classpath_artifacts"]))
    bindings = bind_types(build, types, paths, modules)
    diagnostics = calibrate_architecture.violations(modules, types)
    diagnostics.extend(origin_violations(build, types))
    by_name = {item["name"]: item for item in bindings}
    for item in diagnostics:
        if item.get("type") in by_name:
            binding = by_name[item["type"]]
            item.update(source=binding["source"], purpose=binding["purpose"])
    contracts = select_contracts(modules, types, bindings)
    methods, method_diagnostics, references = method_contracts.bind(build, bindings, contracts, paths)
    diagnostics.extend(method_diagnostics)
    declarations, declaration_diagnostics = dependency_declarations.inspect(build, references, capability_rules)
    diagnostics.extend(declaration_diagnostics)
    declared_contracts_bound = not any(item["code"].startswith("SG_CONTRACT") or item["code"] == "SG_DECLARATION_TARGET" for item in diagnostics)
    calibrate_maven_source.unchanged_build(build, project)
    require(declaration_hash == digest(definition) and before_configuration == configuration(),
            "SG_INPUT_CHANGED", "联合执行期间模块声明或适配配置变化")
    _, after_environment = environment(args)
    require(before_environment == after_environment, "SG_INPUT_CHANGED", "联合执行期间运行环境变化")
    return {"ok": not diagnostics, "proof_scope": "observed-maven-architecture-subset-only",
            "build": build, "bindings": bindings, "contracts": contracts, "inventory": types,
            "method_contracts": methods,
            "dependency_declarations": declarations,
            "source_dependency_declarations_checked": not declaration_diagnostics,
            "source_gate_proven": False,
            "source_bindings_complete": True,
            "module_ownership_bound": not any(item["code"] == "SG_UNKNOWN_OWNER" for item in diagnostics),
            "declared_contracts_bound": declared_contracts_bound,
            "payload_field_closure_checked": not any(item["code"] in ("SG_CONTRACT_TYPE", "SG_UNRESOLVED_TYPE") for item in diagnostics),
            "data_contracts": sorted(name for module in modules.values() for name in module.get("data_contracts", [])),
            "method_scope": {"declared": len({member for module in modules.values()
                                               for member in module["contract_members"] + module.get("critical_methods", []) if "(" in member}),
                             "bound": len(methods)},
            "declared_method_sources_bound": declared_contracts_bound and not any(item["code"] == "SG_METHOD_BINDING" for item in method_diagnostics),
            "declared_method_comments_checked": declared_contracts_bound and not method_diagnostics,
            "architecture_gate_proven": False, "contract_closure_proven": False, "method_documentation_proven": False,
            "semantic_review_proven": False, "publication_proven": False, "human_acceptance": False,
            "declaration": {"path": str(definition.resolve()), "sha256": declaration_hash},
            "configuration": before_configuration, "environment": before_environment, "diagnostics": diagnostics}


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
            _, observed = environment(args)
            report = {"ok": True, "proof_scope": "maven-architecture-environment-only", "environment": observed, "diagnostics": []}
        else:
            report = probe(args)
    except ProbeFailure as error:
        report = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, ET.ParseError) as error:
        report = {"ok": False, "diagnostics": [{"code": "SG_BUILD_INPUT", "message": type(error).__name__}]}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
