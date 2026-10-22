"""已固定模块合同的有限结构检查；不判断 SQL、授权或业务语义。"""

import pathlib
import re
import tempfile
import zipfile

from calibrate_source import digest, require
from calibrate_lifecycle import fixed_version


CATEGORIES = {"neutral", "http", "client", "configuration", "domain-marker"}
DOMAIN_MARKERS = {"org.domainforge.shared." + name for name in ("Entity", "AggregateRoot", "ValueObject")}


def validate(module):
    """schema 4 仅增加精确 API 类别、消费位置和查询方法对应。"""
    categories = module["api_categories"]
    require(isinstance(categories, dict) and set(categories) ==
            (set(module["contract_types"]) if module["kind"] == "component" else set()) and
            all(isinstance(value, str) and value in CATEGORIES for value in categories.values()),
            "SG_COMPONENT_DECLARATION", "组件的每个公开类型须有唯一已知类别，其他模块不声明组件 API")
    uses = module["api_uses"]
    require(isinstance(uses, dict) and all(isinstance(name, str) and name and
            isinstance(positions, list) and positions and
            all(isinstance(position, str) and re.fullmatch(
                r"(?:domain|application|interfaces|infrastructure|bootstrap)(?:\.[A-Za-z_][A-Za-z0-9_]*)*", position)
                for position in positions) and len(positions) == len(set(positions)) for name, positions in uses.items()),
            "SG_COMPONENT_DECLARATION", "消费位置须精确指定上下文内职责包，不接受通配放行")
    require(all(not any(position.split(".")[0] == "domain" for position in positions)
                or name in DOMAIN_MARKERS for name, positions in uses.items()),
            "SG_COMPONENT_POSITION", "领域只准入精确的三个领域标识")
    if "domain-marker" in categories.values():
        require(module["package"] == "org.domainforge.shared"
                and {name for name, category in categories.items() if category == "domain-marker"} == DOMAIN_MARKERS
                and not any(member.startswith(name + ".") for name in DOMAIN_MARKERS
                            for member in module["contract_members"]),
                "SG_DOMAIN_MARKER", "shared 中领域标识只含三个固定接口且无操作成员；其他数据类型须另行分类")
        if "artifact" in module:
            require(module["artifact"].get("group") == "org.domainforge"
                    and module["artifact"].get("artifact") == "shared",
                    "SG_DOMAIN_MARKER", "领域标识制品身份不符")
    queries = module["query_bindings"]
    require(isinstance(queries, list) and all(isinstance(query, dict) and
            set(query) == {"contract", "implementation"} and
            all(isinstance(value, str) and "(" in value for value in query.values()) and
            query["contract"] in module["contract_members"] for query in queries) and
            len(queries) == len({query["implementation"] for query in queries}) and
            (not queries or module["kind"] == "context"),
            "SG_QUERY_DECLARATION", "查询须明确公开方法及唯一实际实现，仅属于 context")
    if "artifact" in module:
        identity = module["artifact"]
        require(module["kind"] == "component" and isinstance(identity, dict) and
                set(identity) == {"group", "artifact", "version", "sha256"} and
                all(isinstance(value, str) and value and not re.search(r"[\s*?\[\]{}]", value)
                    for value in identity.values()) and
                re.fullmatch(r"[a-f0-9]{64}", identity["sha256"]) and fixed_version(identity["version"]) and
                not uses and not queries and not module["depends_on"],
                "SG_COMPONENT_DECLARATION", "外部组件绑定精确 GAV 与本轮制品摘要")


def bind_declaration(path, expected):
    """摘要由调用端的已审阅合同提供；修改声明不能复用原合同或旧报告。"""
    require(isinstance(expected, str) and re.fullmatch(r"[a-f0-9]{64}", expected) and
            path.is_file() and not path.is_symlink() and digest(path) == expected,
            "SG_MODULE_CONTRACT", "模块声明与调用端固定合同不符，须重新检查与语义复审")


def owned(modules):
    return {name: module for name, module in modules.items() if "artifact" not in module}


def external_inventory(report, modules, paths):
    """从同次 Maven 解析制品导入类；不接受自报公开类型或另一个同名 JAR。"""
    import calibrate_architecture

    result = []
    artifacts = {node["file"]: node for compilation in report["build"]["compilations"]
                 for node in compilation["dependencies"]}
    for module in modules.values():
        if "artifact" not in module:
            continue
        identity = module["artifact"]
        matches = [pathlib.Path(path) for path, node in artifacts.items()
                   if all(node[key] == identity[key] for key in ("group", "artifact", "version"))]
        require(len(matches) == 1 and digest(matches[0]) == identity["sha256"] and
                report["build"]["classpath_artifacts"].get(str(matches[0])) == identity["sha256"],
                "SG_COMPONENT_ARTIFACT", "组件来源不是同次构建中的唯一固定制品")
        with zipfile.ZipFile(matches[0]) as archive:
            expected = {name[:-6].replace("/", ".") for name in archive.namelist() if name.endswith(".class")}
        require(expected and all(calibrate_architecture.inside(name, module["package"]) for name in expected),
                "SG_COMPONENT_ARTIFACT", "组件制品含未归属类型或未经校准的类布局")
        # 导入器的目录路径入口不展开 JAR；只复制已核对归属的原始类字节，不重编译组件。
        with tempfile.TemporaryDirectory(prefix="domain-forge-component-import-") as temporary:
            extracted = pathlib.Path(temporary)
            with zipfile.ZipFile(matches[0]) as archive:
                for name in expected:
                    relative = name.replace(".", "/") + ".class"
                    require(".." not in pathlib.PurePosixPath(relative).parts and not relative.startswith("/"),
                            "SG_COMPONENT_ARTIFACT", "组件类路径非法")
                    target = extracted / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(relative))
            roots, inputs, observed = calibrate_architecture.bytecode_inputs([str(extracted)])
            require(observed == expected, "SG_COMPONENT_ARTIFACT", "提取类范围偏离实际制品")
            types = calibrate_architecture.inventory(paths, roots, expected, list(report["build"]["classpath_artifacts"]))
            require(calibrate_architecture.bytecode_inputs([str(extracted)])[1] == inputs,
                    "SG_INPUT_CHANGED", "导入期间组件类字节变化")
        require(digest(matches[0]) == identity["sha256"], "SG_INPUT_CHANGED", "导入期间组件制品变化")
        result.extend(dict(item, component=module["id"], artifact=str(matches[0]), artifact_sha256=identity["sha256"]) for item in types)
    return result


def inspect(report, modules, roles, classify):
    """沿实际类型、成员、源码覆盖关系检查；返回明确违规，不生成语义验收。"""
    from calibrate_architecture import inside

    errors = []
    classes = {item["name"]: item for item in report["inventory"] + report.get("component_inventory", [])}
    bindings = {item["name"]: item for item in report["bindings"]}
    components = {name: module for module in modules.values() if module["kind"] == "component"
                  for name in classes if inside(name, module["package"])}
    categories = {name: category for module in modules.values() for name, category in module["api_categories"].items()}
    for module in modules.values():
        if "domain-marker" not in module["api_categories"].values():
            continue
        actual = {name for name in classes if inside(name, module["package"])
                  and bindings.get(name, {}).get("purpose") != "test"}
        valid = DOMAIN_MARKERS <= actual
        for name in DOMAIN_MARKERS & actual:
            item = classes[name]
            valid &= item.get("interface") == "true" and item["public"] == "true" and not item["members"]
            targets = {ref["target"] for ref in item["dependencies"]}
            parents = {ref["target"] for ref in item["shape_types"] if ref["origin"].endswith(":supertype")}
            expected = {"org.domainforge.shared.Entity"} if name.endswith(".AggregateRoot") else set()
            valid &= targets <= expected | {"java.lang.Object"} and parents - {"java.lang.Object"} == expected
        if not valid:
            errors.append(dict(code="SG_DOMAIN_MARKER", module=module["id"]))
    for module in modules.values():
        for name in module["api_categories"]:
            require(name in classes and classes[name]["public"] == "true", "SG_COMPONENT_TARGET", "组件公开类型缺少真实公开声明：" + name)
        members = {member["id"] for name in module["contract_types"] if name in classes
                   for member in classes[name]["members"] if member["public"] == "true"}
        require(set(module["contract_members"]) <= members, "SG_COMPONENT_TARGET", "公开成员与实际编译签名不一致")
        for name, positions in module["api_uses"].items():
            require(name in categories and all(allowed(categories[name], position) for position in positions),
                    "SG_COMPONENT_POSITION", "调用位置不符合组件类别：" + name)

    def check(owner, target, member=None):
        if owner not in bindings or roles.get(owner) == "test" or target not in components:
            return
        source = modules[bindings[owner]["module"]]
        destination = components[target]
        if source["id"] == destination["id"]:
            return
        detail = dict(type=owner, target=target, source=bindings[owner]["source"])
        if destination["id"] not in source["depends_on"]:
            errors.append(dict(detail, code="SG_UNDECLARED_DEPENDENCY"))
        if target not in categories:
            errors.append(dict(detail, code="SG_INTERNAL_TYPE"))
            return
        if member and member not in destination["contract_members"]:
            errors.append(dict(detail, code="SG_INTERNAL_MEMBER", member=member))
        if source["kind"] != "context":
            return
        position = classes[owner]["package"][len(source["package"]) + 1:]
        permitted = source["api_uses"].get(target, [])
        if not allowed(categories[target], position) or not any(inside(position, item) for item in permitted):
            errors.append(dict(detail, code="SG_COMPONENT_POSITION", category=categories[target], position=position))

    for name, item in classes.items():
        for dependency in item["dependencies"]:
            check(name, dependency["target"])
        for access in item["accesses"]:
            check(name, access["resolved_owner"], access.get("resolved"))
    for reference in report["dependency_declarations"]:
        if reference["usage"] != "import":
            check(reference["owner"], reference["target"])

    # 技术无关合同及查询载荷沿声明形状展开；不把实现体调用误认为公开载荷。
    roots = {name for name, category in categories.items() if category == "neutral"}
    for module in modules.values():
        roots.update(query["contract"].split("(")[0].rsplit(".", 1)[0] for query in module["query_bindings"])
    for root in sorted(roots):
        pending, seen = [root], set()
        while pending:
            name = pending.pop()
            if name in seen or name not in classes:
                continue
            seen.add(name)
            item = classes[name]
            references = list(item["shape_types"])
            references.extend(ref for member in item["members"] if member["public"] == "true"
                              for ref in member["signature_types"])
            references.extend(ref for ref in report["dependency_declarations"]
                              if ref["owner"] == name and ref["usage"] in {"annotation", "annotation-value"})
            for reference in references:
                target = reference["target"]
                if target in classes:
                    target_module = next((module for module in modules.values() if inside(target, module["package"])), None)
                    invalid = categories.get(target) not in {None, "neutral"} or roles.get(target) in {"infrastructure", "bootstrap"}
                    invalid |= bool(target_module and target_module["kind"] == "component" and target not in categories)
                    if not invalid:
                        pending.append(target)
                else:
                    origin = reference.get("origin_uri", "")
                    kind = classify(target, origin)
                    invalid = kind != "neutral"
                if invalid:
                    errors.append(dict(code="SG_COMPONENT_PAYLOAD", type=root, origin=name, target=target))
    methods = {method["id"]: method for method in report.get("method_contracts", [])}
    for module in modules.values():
        for query in module["query_bindings"]:
            contract, implementation = (methods.get(query[key]) for key in ("contract", "implementation"))
            valid = contract and implementation and query["contract"] in implementation["overrides"]
            valid = valid and inside(contract["owner"], module["package"] + ".application.query") and inside(
                implementation["owner"], module["package"] + ".infrastructure.query")
            if not valid:
                errors.append(dict(code="SG_QUERY_IMPLEMENTATION", **query))
    return errors


def allowed(category, position):
    """类别限定上界；具体消费者还须声明实际需要的职责子包。"""
    role = position.split(".")[0]
    if category == "domain-marker":
        return role in {"domain", "application", "interfaces", "infrastructure"}
    if category == "neutral":
        return role in {"application", "interfaces", "infrastructure"}
    if category == "http":
        return position == "interfaces.http" or position.startswith("interfaces.http.") or role == "infrastructure"
    if category == "client":
        return role == "infrastructure"
    return category == "configuration" and role == "bootstrap"
