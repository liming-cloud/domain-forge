"""限定字节码与集中声明的真实架构校准，不作为产品构建或源码用途证明。"""

import argparse
import json
import os
import pathlib
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

from calibrate_source import HERE, ProbeFailure, digest, execute, require, toolchain


def declaration(path, schema=1):
    """集中声明严格校验；不接受目录通配或隐式全公开合同。"""
    def unique(pairs):
        values = {}
        for key, value in pairs:
            require(key not in values, "SG_DECLARATION", "模块声明有重复字段")
            values[key] = value
        return values

    try:
        require(path.is_file() and not path.is_symlink(), "SG_DECLARATION", "模块声明缺失或为符号链接")
        definition = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeError, ValueError) as error:
        raise ProbeFailure("SG_DECLARATION", "模块声明无法读取") from error
    require(isinstance(definition, dict) and set(definition) == {"schema", "modules"} and
            type(definition["schema"]) is int and definition["schema"] == schema and
            isinstance(definition["modules"], list) and definition["modules"], "SG_DECLARATION", "模块声明为空或版本无效")
    modules = {}
    for item in definition["modules"]:
        fields = {"id", "package", "depends_on", "contract_types", "contract_members"}
        if schema == 1:
            fields.add("purpose")
        if schema in (3, 4):
            fields.add("kind")
        if schema == 4:
            fields.update(("api_categories", "api_uses", "query_bindings"))
        optional = {"data_contracts"} | ({"critical_methods"} if schema != 1 else set()) | ({"artifact"} if schema == 4 else set())
        require(isinstance(item, dict) and fields <= set(item) <= fields | optional,
                "SG_DECLARATION", "模块字段缺失或未知")
        require(isinstance(item["id"], str) and re.fullmatch(r"[a-z][a-z0-9-]*", item["id"]) and item["id"] not in modules and
                isinstance(item["package"], str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", item["package"]) and
                (schema != 1 or item["purpose"] in ("production", "test")), "SG_DECLARATION", "模块身份、包根或用途无效")
        require(schema not in (3, 4) or item["kind"] in ("context", "component", "assembly"),
                "SG_DECLARATION", "模块性质未知，不能用任意名称豁免职责")
        for key in ("depends_on", "contract_types", "contract_members", "data_contracts", "critical_methods"):
            value = item.get(key, [])
            require(isinstance(value, list) and all(isinstance(part, str) and part.strip() for part in value) and
                    len(value) == len(set(value)), "SG_DECLARATION", "模块列表须为唯一非空字符串")
        require(set(item.get("data_contracts", [])) <= set(item["contract_types"]),
                "SG_DECLARATION", "数据合同须为同一模块已声明的公开类型")
        if schema == 4:
            import structural_contracts
            structural_contracts.validate(item)
        modules[item["id"]] = item
    for identifier, item in modules.items():
        require(all(target in modules and target != identifier for target in item["depends_on"]), "SG_DECLARATION", "模块依赖目标未知或指向自身")
        for other in modules.values():
            require(other is item or not (inside(item["package"], other["package"]) or inside(other["package"], item["package"])),
                    "SG_DECLARATION", "不同模块包根重叠，归属不唯一")
    return modules


def inside(name, root):
    return name == root or name.startswith(root + ".")


def bytecode_inputs(raw_roots):
    """逐个实际类文件快照，空目录、别名、损坏或未验证语言形式明确拒绝。"""
    require(raw_roots, "SG_BYTECODE_INPUT", "没有显式字节码目录")
    roots = []
    for raw in raw_roots:
        path = pathlib.Path(raw)
        require(path.is_dir() and not path.is_symlink(), "SG_BYTECODE_INPUT", "字节码根不是真实目录")
        roots.append(path.resolve())
    require(len(set(roots)) == len(roots) and not any(left != right and left.is_relative_to(right) for left in roots for right in roots),
            "SG_BYTECODE_INPUT", "字节码目录重复或重叠")
    inputs, expected = {}, set()
    for root in roots:
        require(all(not item.is_symlink() for item in root.rglob("*")), "SG_BYTECODE_INPUT", "字节码目录含符号链接，不能静默漏扫")
        files = sorted(root.rglob("*.class"))
        require(files, "SG_BYTECODE_INPUT", "字节码根没有实际类文件")
        for path in files:
            require(path.is_file() and not path.is_symlink() and path.resolve() == path, "SG_BYTECODE_INPUT", "字节码输入含符号链接或不规则文件")
            data = path.read_bytes()
            require(len(data) > 8 and data[:4] == b"\xca\xfe\xba\xbe" and data[4:6] == b"\x00\x00" and
                    int.from_bytes(data[6:8], "big") == 65 and path.stem not in ("module-info", "package-info"),
                    "SG_BYTECODE_INPUT", "本轮只校准 Java 21 普通类文件；其他形式尚未验证")
            name = path.relative_to(root).with_suffix("").as_posix().replace("/", ".")
            require(name not in expected, "SG_BYTECODE_INPUT", "多个输入包含同名类型")
            expected.add(name)
            inputs[str(path)] = digest(path)
    return roots, inputs, expected


def inventory(paths, roots, expected, dependencies=()):
    """真实编译适配器并导入全部对象，检查实际类型集合与文件清单一致。"""
    jars = [paths[name] for name in ("archunit", "slf4j-api", "slf4j-nop")]
    with tempfile.TemporaryDirectory(prefix="domain-forge-architecture-tool-") as temporary:
        compiled = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", os.pathsep.join(jars),
                            "-d", temporary, str(HERE / "ArchitectureInventory.java")])
        require(compiled.returncode == 0, "SG_TOOL_FAILURE", "字节码适配器实际编译失败：" + compiled.stderr)
        process = execute(["java", "-cp", os.pathsep.join([temporary, *jars]), "ArchitectureInventory",
                           os.pathsep.join([*map(str, roots), *dependencies]), *map(str, roots)])
    require(process.returncode == 0 and not process.stderr, "SG_TOOL_FAILURE", "真实架构工具执行失败：" + process.stderr)
    try:
        output = ET.fromstring(process.stdout)
        types = output.findall("class")
        require(output.tag == "inventory" and len(types) == len(expected) and {item.attrib["name"] for item in types} == expected,
                "SG_SCAN_COVERAGE", "实际类型集合与类文件输入不一致")
        return [dict(item.attrib, members=[dict(member.attrib, signature_types=[ref.attrib for ref in member.findall("signature_type")])
                                          for member in item.findall("member")],
                     shape_types=[reference.attrib for reference in item.findall("shape_type")],
                     dependencies=[dependency.attrib for dependency in item.findall("dependency")],
                     accesses=[access.attrib for access in item.findall("access")]) for item in types]
    except (ET.ParseError, KeyError) as error:
        raise ProbeFailure("SG_TOOL_FAILURE", "架构工具输出无效") from error


def violations(modules, types):
    """检查真实归属、依赖和合同数据字段闭包；动态合同及业务语义另行验证。"""
    errors, owners, members = [], {}, {}

    def issue(code, **detail):
        errors.append(dict(code=code, **detail))

    for item in types:
        matches = [identifier for identifier, module in modules.items() if inside(item["name"], module["package"])]
        if len(matches) != 1:
            issue("SG_UNKNOWN_OWNER", type=item["name"])
            continue
        owners[item["name"]] = matches[0]
        members.update((member["id"], (item["name"], member)) for member in item["members"])
    by_name = {item["name"]: item for item in types}
    for identifier, module in modules.items():
        if identifier not in owners.values():
            issue("SG_DECLARATION_TARGET", module=identifier)
        for name in module["contract_types"]:
            if owners.get(name) != identifier or by_name[name]["public"] != "true":
                issue("SG_CONTRACT_TARGET", target=name)
            elif by_name[name].get("purpose", "production") != "production":
                issue("SG_CONTRACT_PURPOSE", target=name)
        for name in module["contract_members"]:
            member = members.get(name)
            if not member or member[0] not in module["contract_types"] or member[1]["public"] != "true":
                issue("SG_CONTRACT_TARGET", target=name)

    edges = {identifier: set() for identifier in modules}

    def dependency_boundary(origin, origin_name, target, purpose):
        destination = owners.get(target)
        if not destination:
            return
        if purpose == "production" and by_name[target].get("purpose", modules[destination].get("purpose")) == "test":
            issue("SG_TEST_DEPENDENCY", origin=origin_name, target=target)
        if destination == origin:
            return
        edges[origin].add(destination)
        if destination not in modules[origin]["depends_on"]:
            issue("SG_UNDECLARED_DEPENDENCY", origin=origin_name, target=target)
        if target not in modules[destination]["contract_types"]:
            issue("SG_INTERNAL_TYPE", origin=origin_name, target=target)

    for item in types:
        origin = owners.get(item["name"])
        if not origin:
            continue
        purpose = item.get("purpose", modules[origin].get("purpose"))
        if item["fully_imported"] != "true":
            issue("SG_UNRESOLVED_TYPE", origin=item["name"], target=item["name"])
        for dependency in item["dependencies"]:
            target = dependency["target"]
            if dependency["fully_imported"] != "true":
                issue("SG_UNRESOLVED_TYPE", origin=item["name"], target=target)
            dependency_boundary(origin, item["name"], target, purpose)
        for access in item["accesses"]:
            dependency_boundary(origin, access["origin"], access["target_owner"], purpose)
            destination = owners.get(access["resolved_owner"])
            if destination and destination != origin:
                dependency_boundary(origin, access["origin"], access["resolved_owner"], purpose)
                if access["resolved"] not in modules[destination]["contract_members"]:
                    issue("SG_INTERNAL_MEMBER", origin=access["origin"], target=access["resolved"])
            elif not access["resolved"] and owners.get(access["target_owner"]) not in (None, origin):
                issue("SG_MEMBER_RESOLUTION", origin=access["origin"], target=access["target"])
    errors.extend(payload_violations(modules, by_name, owners))

    def visit(node, path, done):
        if node in path:
            issue("SG_MODULE_CYCLE", path=path[path.index(node):] + [node])
        elif node not in done:
            for target in sorted(edges[node]):
                visit(target, path + [node], done)
            done.add(node)

    done = set()
    for node in sorted(edges):
        visit(node, [], done)
    return errors


def payload_references(modules, types, owners):
    """沿直接签名与显式数据合同展开，复用同一依赖图，不猜测业务角色。"""
    data = {name for module in modules.values() for name in module.get("data_contracts", [])}
    roots = [(name, member["id"], member["signature_types"]) for name, owner in owners.items()
             for member in types[name]["members"] if member["id"] in modules[owner]["contract_members"]]
    roots.extend((name, name, [dict(target=name, fully_imported=types[name]["fully_imported"])])
                 for name in sorted(data) if name in owners)
    for name, origin, references in roots:
        pending = [(reference, [origin], False) for reference in references]
        visited = set()
        while pending:
            reference, path, payload = pending.pop()
            target = reference["target"]
            payload = payload or target in data
            yield name, origin, reference, path
            destination = owners.get(target)
            if not destination or (target, payload) in visited:
                continue
            visited.add((target, payload))
            if target not in modules[destination]["contract_types"]:
                continue
            if payload:
                pending.extend((child, path + [child["origin"]], True) for child in types[target]["shape_types"])


def payload_violations(modules, types, owners):
    """核对直接签名和数据图的可达自有类型，保留违规路径。"""
    errors = []
    for name, origin, reference, path in payload_references(modules, types, owners):
        target = reference["target"]
        if reference["fully_imported"] != "true":
            errors.append(dict(code="SG_UNRESOLVED_TYPE", type=name, origin=origin, target=target, path=path))
        destination = owners.get(target)
        if destination and target not in modules[destination]["contract_types"]:
            errors.append(dict(code="SG_CONTRACT_TYPE", type=name, origin=origin, target=target, path=path + [target]))
    return errors


def probe(args):
    definition = pathlib.Path(args.declaration)
    modules = declaration(definition)
    roots, inputs, expected = bytecode_inputs(args.roots)
    inputs[str(definition.resolve())] = digest(definition)
    configuration = {name: digest(HERE / name) for name in ("tools.lock.json", "ArchitectureInventory.java", "calibrate_architecture.py", "calibrate_source.py")}
    paths, environment = toolchain(args.tools)
    types = inventory(paths, roots, expected)
    diagnostics = violations(modules, types)
    _, after_inputs, after_expected = bytecode_inputs(args.roots)
    after_inputs[str(definition.resolve())] = digest(definition)
    require(inputs == after_inputs and expected == after_expected and
            configuration == {name: digest(HERE / name) for name in configuration}, "SG_INPUT_CHANGED", "执行期间架构输入或工具适配发生变化")
    _, after_environment = toolchain(args.tools)
    require(environment == after_environment, "SG_INPUT_CHANGED", "执行期间工具或运行时变化")
    return {"ok": not diagnostics, "proof_scope": "explicit-bytecode-architecture-subset-only",
            "source_ownership_proven": False, "build_proven": False, "contract_closure_proven": False,
            "human_acceptance": False, "semantic_review_proven": False,
            "scanned": sorted(path for path in inputs if path.endswith(".class")),
            "inputs": inputs, "configuration": configuration, "environment": environment,
            "inventory": types, "diagnostics": diagnostics}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tools", default=os.environ.get("DOMAIN_FORGE_GATE_TOOLS"))
    parser.add_argument("--declaration", required=True)
    parser.add_argument("roots", nargs="*")
    args = parser.parse_args()
    try:
        report = probe(args)
    except ProbeFailure as error:
        report = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError) as error:
        report = {"ok": False, "diagnostics": [{"code": "SG_INPUT", "message": type(error).__name__}]}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
