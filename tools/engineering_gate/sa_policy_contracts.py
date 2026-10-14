"""原生有效声明、独立场景和实际注册的限定绑定；不实现产品鉴权或策略语言。"""

import json
import xml.etree.ElementTree as ET

import calibrate_http
from calibrate_source import require


def canonical(conditions):
    require(isinstance(conditions, list), "SG_ACCESS_POLICY_INPUT", "条件必须显式列出")
    normalized = []
    for item in conditions:
        require(isinstance(item, dict) and item.get("kind") in {"login", "permission"},
                "SG_ACCESS_POLICY_INPUT", "只接受当前原生注解校准条件")
        fields = {"kind", "type"} | ({"mode", "values", "or_roles"} if item["kind"] == "permission" else set())
        require(set(item) == fields and item["type"] == "", "SG_ACCESS_POLICY_INPUT", "其他账号体系或未知字段尚未校准")
        value = dict(item)
        if item["kind"] == "permission":
            require(item["mode"] in {"AND", "OR"} and item["or_roles"] == [] and isinstance(item["values"], list) and
                    item["values"] and all(isinstance(code, str) and code.strip() for code in item["values"]),
                    "SG_ACCESS_POLICY_INPUT", "权限条件须非空；角色替代等其他形式须先校准")
            value["values"] = sorted(item["values"])
        normalized.append(json.dumps(value, sort_keys=True))
    return sorted(normalized)


def credential(case):
    headers = {key.lower(): value for key, value in case["request"]["headers"].items()}
    require(len(headers) == len(case["request"]["headers"]) and "cookie" not in headers,
            "SG_ACCESS_POLICY_SCENARIOS", "策略场景只能使用唯一 Header 身份，不用 Cookie 冒充")
    value = headers.get("authorization")
    require(value is None or value.startswith("Bearer ") and value.removeprefix("Bearer "),
            "SG_ACCESS_POLICY_SCENARIOS", "策略场景须为匿名或明确 Bearer 身份")
    return value


def definition(path, cases):
    source = calibrate_http.read_json(path)
    require(set(source) == {"schema", "entries"} and type(source["schema"]) is int and source["schema"] == 1 and
            isinstance(source["entries"], list) and source["entries"], "SG_ACCESS_POLICY_INPUT", "需要独立完整的原生策略合同")
    by_id = {item["id"]: item for item in cases}
    identities = set()
    for entry in source["entries"]:
        require(isinstance(entry, dict) and set(entry) == {"path", "method", "kind", "conditions", "cases", "login_witness"} and
                isinstance(entry["path"], str) and isinstance(entry["method"], str) and
                entry["kind"] in {"anonymous", "login", "permission"}, "SG_ACCESS_POLICY_INPUT", "入口须固定类别、条件及场景")
        identity = (entry["path"], entry["method"])
        require(identity not in identities, "SG_ACCESS_POLICY_INPUT", "策略入口重复")
        identities.add(identity)
        conditions = canonical(entry["conditions"])
        kinds = {item["kind"] for item in entry["conditions"]}
        require((entry["kind"] == "anonymous" and not conditions or entry["kind"] == "login" and kinds == {"login"} or
                 entry["kind"] == "permission" and "permission" in kinds), "SG_ACCESS_POLICY_INPUT", "策略类别和条件矛盾")
        roles = {"allow"} if entry["kind"] == "anonymous" else {"allow", "anonymous"} | ({"forbidden"} if entry["kind"] == "permission" else set())
        require(isinstance(entry["cases"], dict) and set(entry["cases"]) == roles and
                all(isinstance(value, str) and value in by_id for value in entry["cases"].values()) and
                len(set(entry["cases"].values())) == len(roles), "SG_ACCESS_POLICY_SCENARIOS", "策略必需允许与拒绝场景不得省略、重复或虚构")
        selected = {role: by_id[identifier] for role, identifier in entry["cases"].items()}
        require(all(case["endpoint"] == list(identity) and case["valid_request"] for case in selected.values()),
                "SG_ACCESS_POLICY_SCENARIOS", "策略场景须完整验证同一真实入口，不把非法请求当权限反例")
        allow = selected["allow"]
        if entry["kind"] == "anonymous":
            require(credential(allow) is None and allow["status"] < 500 and allow["status"] not in {401, 403},
                    "SG_ACCESS_POLICY_SCENARIOS", "匿名正例必须真实不携带身份且不被访问拒绝")
            require(entry["login_witness"] is None, "SG_ACCESS_POLICY_INPUT", "匿名策略不附加身份见证")
        else:
            require(credential(allow) and 200 <= allow["status"] < 300 and credential(selected["anonymous"]) is None and
                    selected["anonymous"]["status"] == 401, "SG_ACCESS_POLICY_SCENARIOS", "受保护入口必须区分有效身份与匿名")
            if entry["kind"] == "permission":
                forbidden = selected["forbidden"]
                witness = by_id.get(entry["login_witness"])
                require(credential(forbidden) and credential(forbidden) != credential(allow) and forbidden["status"] == 403 and
                        witness and credential(witness) == credential(forbidden) and 200 <= witness["status"] < 300 and
                        any(item["kind"] == "login" and witness["endpoint"] == [item["path"], item["method"]] for item in source["entries"]),
                        "SG_ACCESS_POLICY_SCENARIOS", "缺权限主体须由同一有效身份的登录入口成功见证，不能用失效令牌冒充")
            else:
                require(entry["login_witness"] is None, "SG_ACCESS_POLICY_INPUT", "仅登录策略不附加权限见证")
    return source


def observed(node):
    conditions, active = [], True
    for item in node.findall("condition"):
        kind = item.get("kind")
        value = {"kind": kind, "type": item.get("type")}
        expected = "cn.dev33.satoken.annotation.handler.SaCheck" + ("Login" if kind == "login" else "Permission") + "Handler"
        active &= item.get("handler") == expected
        if kind == "permission":
            value.update(mode=item.get("mode"), values=[child.get("code") for child in item.findall("value")],
                         or_roles=[child.get("code") for child in item.findall("or-role")])
        conditions.append(value)
    return conditions, active


def assess(source, report, cases, observation):
    document = ET.parse(observation).getroot()
    nodes = document.findall("access-policy")
    by_handler = {(item.get("owner"), item.get("handler")): item for item in nodes}
    require(len(by_handler) == len(nodes), "SG_ACCESS_POLICY_OBSERVATION", "原生策略观察重复")
    actual = {(item["path"], item["method"]): item for item in report["registered"]}
    expected = {(item["path"], item["method"]): item for item in source["entries"]}
    errors, policies = default_deny(document), []
    if set(actual) != set(expected):
        errors.append({"code": "SG_ACCESS_POLICY_COVERAGE"})
    interactions = {item["scenario"]: item for item in report["interactions"]}
    for identity, entry in actual.items():
        node = by_handler.get((entry["owner"], entry["handler"]))
        require(node is not None and node.get("anonymous") in {"true", "false"},
                "SG_ACCESS_POLICY_OBSERVATION", "实际注册处理器缺少原生策略观察")
        conditions, active = observed(node)
        invalid = any(item["kind"] == "permission" and (not item["values"] or any(not code.strip() for code in item["values"])) for item in conditions)
        kind = "anonymous" if node.get("anonymous") == "true" else "permission" if any(item["kind"] == "permission" for item in conditions) else "login" if conditions else "missing"
        policies.append({"path": identity[0], "method": identity[1], "kind": kind, "condition_count": len(conditions)})
        if invalid:
            errors.append({"code": "SG_ACCESS_POLICY_EMPTY", "path": identity[0], "method": identity[1]})
            continue
        if kind == "missing":
            errors.append({"code": "SG_ACCESS_POLICY_MISSING", "path": identity[0], "method": identity[1]})
            identifiers = set(expected.get(identity, {}).get("cases", {}).values())
            selected = [case for case in cases if case["id"] in identifiers]
            if not {bool(credential(case)) for case in selected}.issuperset({False, True}):
                errors.append({"code": "SG_ACCESS_DEFAULT_DENY_SCENARIOS", "path": identity[0], "method": identity[1]})
            elif any(interactions[case["id"]]["status"] != 403 for case in selected):
                errors.append({"code": "SG_ACCESS_DEFAULT_DENY", "path": identity[0], "method": identity[1]})
            continue
        if not active or node.findall("unsupported-policy") or any(item["type"] or item.get("or_roles") for item in conditions):
            errors.append({"code": "SG_ACCESS_POLICY_SCOPE", "path": identity[0], "method": identity[1]})
            continue
        declared = expected.get(identity)
        if declared and (kind != declared["kind"] or canonical(conditions) != canonical(declared["conditions"])):
            errors.append({"code": "SG_ACCESS_POLICY_DRIFT", "path": identity[0], "method": identity[1]})
    return errors, policies


def default_deny(document):
    probes = document.findall("default-deny-probe")
    require(len(probes) == 1, "SG_ACCESS_DEFAULT_DENY_OBSERVATION", "需要实际临时未声明探针，不以现有声明齐全推定默认拒绝")
    probe = probes[0]
    require(all(probe.get(field, "").strip() for field in ("path", "owner", "handler")),
            "SG_ACCESS_DEFAULT_DENY_OBSERVATION", "实际探针身份必须完整，不能以两侧缺失视为分派相同")
    responses = probe.findall("response")
    require(len(responses) == 2 and {item.get("scenario") for item in responses} == {"__default_deny_anonymous", "__default_deny_authenticated"} and
            all(item.get("dispatched-pattern") == probe.get("path") and item.get("dispatched-owner") == probe.get("owner") and
                item.get("dispatched-handler") == probe.get("handler") for item in responses),
            "SG_ACCESS_DEFAULT_DENY_OBSERVATION", "默认拒绝必须来自实际探针分派，不能以未知路由或前置失败代替")
    return [] if all(item.get("status") == "403" for item in responses) else [{"code": "SG_ACCESS_DEFAULT_DENY"}]
