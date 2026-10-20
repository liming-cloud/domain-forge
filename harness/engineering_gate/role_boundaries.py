"""默认上下文职责与已校准技术引用的有限检查，不判断业务归属和全部合同语义。"""

import json
import urllib.parse
import urllib.request

from calibrate_architecture import inside, payload_references
from calibrate_lifecycle import fixed_version
from calibrate_source import HERE, ProbeFailure, require
import structural_contracts


ROLES = {"interfaces", "application", "domain", "infrastructure", "bootstrap"}
USAGES = {"import", "annotation", "annotation-value", "type"}


def baseline():
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "SG_ROLE_BASELINE", "技术基线字段重复")
            result[key] = value
        return result

    try:
        value = json.loads((HERE / "role-boundaries.lock.json").read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeError, ValueError) as failure:
        raise ProbeFailure("SG_ROLE_BASELINE", "职责校准基线无法读取") from failure
    fields = {"schema", "scope", "spring_version", "technical_types", "application_annotations", "annotation_values", "utility_libraries", "technical_libraries", "sources"}
    require(isinstance(value, dict) and set(value) == fields and type(value["schema"]) is int and value["schema"] == 1 and
            value["scope"] == "isolated-context-role-calibration-not-product-baseline" and fixed_version(value["spring_version"]),
            "SG_ROLE_BASELINE", "职责校准基线字段或版本无效")
    for key in ("technical_types", "application_annotations", "sources"):
        parts = value[key]
        require(isinstance(parts, list) and parts and all(isinstance(item, str) and item and
                not any(character in item for character in "*?[]{} ") for item in parts) and len(parts) == len(set(parts)),
                "SG_ROLE_BASELINE", "职责校准列表无效或含泛化放行")
    values = value["annotation_values"]
    require(isinstance(values, dict) and values and all(annotation in value["application_annotations"] and
            isinstance(parts, list) and parts and all(isinstance(part, str) and part.startswith("org.springframework.") and
            not any(character in part for character in "*?[]{} ") for part in parts) and len(parts) == len(set(parts))
            for annotation, parts in values.items()), "SG_ROLE_BASELINE", "注解值须绑定精确已校准注解")
    value_types = {part for parts in values.values() for part in parts}
    require(all(item.startswith("https://") for item in value["sources"]) and
            all(item.startswith("org.springframework.") for item in value["application_annotations"] + value["technical_types"]) and
            not set(value["application_annotations"]) & value_types and
            not set(value["technical_types"]) & (set(value["application_annotations"]) | value_types),
            "SG_ROLE_BASELINE", "职责校准缺少官方依据或精确框架类型")
    libraries = []
    for key in ("utility_libraries", "technical_libraries"):
        parts = value[key]
        require(isinstance(parts, list) and all(isinstance(item, dict) and set(item) == {"group", "artifact", "version"} and
                all(isinstance(part, str) and part and not any(character in part for character in "*?[]{} ") for part in item.values()) and
                fixed_version(item["version"]) for item in parts), "SG_ROLE_BASELINE", "库须有精确实际身份")
        libraries.extend(parts)
    require(len(libraries) == len({tuple(item[key] for key in ("group", "artifact", "version")) for item in libraries}),
            "SG_ROLE_BASELINE", "库身份重复或技术与通用分类冲突")
    return value


def inspect(report, modules, rules):
    """复用原生类型、源码引用与依赖身份；未知技术组合是缺口，不是假定无违规。"""
    errors, roles = [], {}
    classes = {item["name"]: item for item in report["inventory"]}
    bindings = {item["name"]: item for item in report["bindings"]}
    references = report["dependency_declarations"]
    require(all(item.get("usage") in USAGES and isinstance(item.get("owner"), str) and
            (item["usage"] == "import" and not item["owner"] or item["owner"] in bindings and
             bindings[item["owner"]]["source"] == item["file"] and bindings[item["owner"]]["purpose"] == item["purpose"])
            for item in references),
            "SG_ROLE_SOURCE", "源码引用缺少原生使用位置及所属类型")
    for name, binding in bindings.items():
        if binding["module"] is None:
            continue
        module = modules[binding["module"]]
        role = "test" if binding["purpose"] == "test" else module["kind"]
        if role == "context":
            matches = [item for item in sorted(ROLES) if inside(classes[name]["package"], module["package"] + "." + item)]
            role = matches[0] if len(matches) == 1 else "unknown"
            if role == "unknown":
                errors.append({"code": "SG_ROLE_OWNERSHIP", "type": name, "source": binding["source"]})
        roles[name] = role
    artifacts = {node["file"]: node for item in report["build"]["compilations"] for node in item["dependencies"]}
    strict = all("api_categories" in module for module in modules.values())
    component_types = {item["name"] for item in report.get("component_inventory", [])} | {
        name for name, binding in bindings.items() if binding["module"] is not None and
        modules[binding["module"]]["kind"] == "component"}
    uses, annotations = {}, {}
    for reference in references:
        uses.setdefault((reference["owner"], reference["target"]), set()).add(reference["usage"])
        if reference["usage"] in {"annotation", "annotation-value"}:
            annotations.setdefault(reference["owner"], []).append(reference)

    def classification(target, origin):
        if target in roles:
            return "owned"
        uri = urllib.parse.urlsplit(origin)
        if uri.scheme == "jrt" or not origin:
            return "technical" if any(inside(target, prefix) for prefix in ("java.sql", "javax.sql", "java.net.http")) else "neutral"
        artifact = artifacts.get(urllib.request.url2pathname(uri.path))
        require(uri.scheme == "file" and artifact is not None, "SG_ROLE_SOURCE", "技术引用缺少同次原生依赖身份")
        if artifact["group"] == "org.springframework":
            calibrated = set(rules["technical_types"] + rules["application_annotations"]) | {
                part for parts in rules["annotation_values"].values() for part in parts}
            return "technical" if artifact["version"] == rules["spring_version"] and target in calibrated else "uncalibrated"
        identity = {key: artifact[key] for key in ("group", "artifact", "version")}
        if identity in rules["technical_libraries"]:
            return "technical"
        return "neutral" if identity in rules["utility_libraries"] else "uncalibrated"

    def check(owner, target, origin="", operation=False, usage=None, metadata_path=False):
        role = roles.get(owner)
        if role not in ROLES:
            return
        detail = {"type": owner, "target": target, "role": role, "source": bindings[owner]["source"]}
        if strict and target in component_types:
            return  # 精确组件合同在结构检查中按调用位置统一核对。
        destination = roles.get(target)
        if destination in ROLES:
            allowed = {"domain": {"domain"}, "application": {"application", "domain"},
                       "interfaces": {"interfaces", "application", "domain"},
                       "infrastructure": ROLES - {"bootstrap"}, "bootstrap": ROLES}
            if destination not in allowed[role]:
                errors.append(dict(detail, code="SG_ROLE_DEPENDENCY", target_role=destination))
            return
        if role == "interfaces":
            # HTTP 技术属于入口；已校准的 SQL／客户端库仍须留在基础设施。
            metadata_types = set(rules["application_annotations"]) | {
                value for values in rules["annotation_values"].values() for value in values}
            kind = classification(target, origin)
            artifact = artifacts.get(urllib.request.url2pathname(urllib.parse.urlsplit(origin).path), {})
            known_client = target in rules["technical_types"] or any(
                all(artifact.get(key) == library[key] for key in ("group", "artifact"))
                for library in rules["technical_libraries"])
            if kind == "uncalibrated" and known_client:
                errors.append(dict(detail, code="SG_ROLE_CALIBRATION"))
            elif kind == "technical" and target not in metadata_types:
                errors.append(dict(detail, code="SG_TECHNICAL_DEPENDENCY", usage=usage or "bytecode", operation=operation))
            return
        if role not in {"application", "domain"}:
            return
        kind = classification(target, origin)
        if kind in {"owned", "neutral"}:
            return
        if kind == "uncalibrated":
            errors.append(dict(detail, code="SG_ROLE_CALIBRATION"))
            return
        observed = uses.get((owner, target), set()) - {"import"}
        value_of = [annotation for annotation, values in rules["annotation_values"].items() if target in values]
        default_value = not observed and any("annotation" in uses.get((owner, annotation), set()) for annotation in value_of)
        metadata = ((target in rules["application_annotations"] and
                     (observed == {"annotation"} or metadata_path and usage == "annotation")) or
                    (value_of and (observed == {"annotation-value"} or default_value or
                                   metadata_path and usage == "annotation-value")))
        if role == "application" and not operation and metadata and (usage is None or usage != "type"):
            return
        errors.append(dict(detail, code="SG_TECHNICAL_DEPENDENCY", usage=usage or "bytecode", operation=operation))

    for owner, item in classes.items():
        for dependency in item["dependencies"]:
            check(owner, dependency["target"], dependency.get("origin_uri", ""))
        for access in item["accesses"]:
            if access["resolved_owner"]:
                check(owner, access["resolved_owner"], access.get("resolved_origin_uri", ""), operation=True)
    def reference_origin(reference):
        return ("jrt:/" + reference.get("module", "")) if reference["kind"] == "platform" else reference.get("origin_uri", "")

    def follow_annotations(consumer, owner, visited):
        if owner in visited:
            return
        visited.add(owner)
        for reference in annotations.get(owner, []):
            target = reference["target"]
            check(consumer, target, reference_origin(reference), usage=reference["usage"], metadata_path=True)
            if reference["usage"] == "annotation" and target in roles:
                follow_annotations(consumer, target, visited)

    for reference in references:
        if reference["usage"] == "import":
            continue
        check(reference["owner"], reference["target"], reference_origin(reference), usage=reference["usage"])
        if reference["usage"] == "annotation" and reference["target"] in roles:
            follow_annotations(reference["owner"], reference["target"], set())
    owners = {name: binding["module"] for name, binding in bindings.items() if binding["module"] is not None}
    for name, origin, reference, path in payload_references(modules, classes, owners):
        if roles.get(name) not in {"interfaces", "application", "domain"}:
            continue
        target = reference["target"]
        kind = "owned" if strict and target in component_types else classification(target, reference.get("origin_uri", ""))
        if kind == "technical" or roles.get(target) == "infrastructure":
            errors.append(dict(code="SG_TECHNICAL_CONTRACT", type=name, origin=origin, target=target, path=path + [target]))
        elif kind == "uncalibrated":
            errors.append(dict(code="SG_ROLE_CALIBRATION", type=name, origin=origin, target=target, path=path + [target]))
    if strict:
        errors.extend(structural_contracts.inspect(report, modules, roles, classification))
    return roles, errors
