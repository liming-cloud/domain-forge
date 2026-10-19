"""源码引用、原生 Maven 身份及已校准能力 API 的有限关联，不判定全部源码语义。"""

import urllib.parse
import urllib.request

import calibrate_lifecycle
from calibrate_source import require


IDENTITY = ("group", "artifact", "version", "extension", "classifier")


def capabilities():
    """只接收精确且有官方依据的已校准组合，版本复用唯一工具基线。"""
    baseline = calibrate_lifecycle.baseline()
    require(isinstance(baseline, dict), "SG_DEPENDENCY_CAPABILITY", "能力 API 基线不是对象")
    rules = baseline.get("dependency_capabilities")
    require(isinstance(rules, list) and rules, "SG_DEPENDENCY_CAPABILITY", "缺少能力 API 校准关系")
    result = []
    for rule in rules:
        require(isinstance(rule, dict) and set(rule) == {"provider", "apis", "source"} and
                isinstance(rule["apis"], list) and rule["apis"] and isinstance(rule["source"], str) and
                rule["source"].startswith("https://"), "SG_DEPENDENCY_CAPABILITY", "能力 API 关系不完整")
        coordinates = []
        for entry in [rule["provider"], *rule["apis"]]:
            require(isinstance(entry, dict) and set(entry) == {"group", "artifact", "version_key"} and
                    all(isinstance(value, str) and value and not any(character in value for character in "*?[]{} ")
                        for value in entry.values()) and calibrate_lifecycle.fixed_version(baseline.get(entry["version_key"])),
                    "SG_DEPENDENCY_CAPABILITY", "能力 API 必须绑定精确坐标及唯一固定版本")
            coordinates.append((entry["group"], entry["artifact"], baseline[entry["version_key"]], "jar", ""))
        require(len(set(coordinates)) == len(coordinates), "SG_DEPENDENCY_CAPABILITY", "能力入口与 API 重复")
        result.extend((coordinates[0], api, rule["source"]) for api in coordinates[1:])
    require(len(result) == len({(provider, api) for provider, api, _ in result}),
            "SG_DEPENDENCY_CAPABILITY", "能力 API 关系重复")
    return result


def inspect(build, references, rules):
    """按源码实际用途核对声明；原生 SDK 的内部继承和间接实现不增加声明责任。"""
    sources = {path: item for item in build["compilations"] for path in item["sources"]}
    direct = build["direct_dependencies"]
    results, diagnostics = [], []
    for reference in references:
        require(reference["file"] in sources and reference.get("kind") in {"source", "platform", "classpath"},
                "SG_DEPENDENCY_DECLARATION", "源码引用缺少实际编译来源")
        compilation = sources[reference["file"]]
        detail = dict(reference, purpose=compilation["purpose"])
        if reference["kind"] != "classpath":
            results.append(dict(detail, basis=reference["kind"]))
            continue
        uri = urllib.parse.urlsplit(reference.get("origin_uri", ""))
        path = urllib.request.url2pathname(uri.path)
        candidates = [node for node in compilation["dependencies"] if node["file"] == path and path in compilation["classpath"]]
        if uri.scheme != "file" or uri.netloc or uri.query or uri.fragment or path not in build["classpath_artifacts"] or len(candidates) != 1:
            diagnostics.append(dict(detail, code="SG_DEPENDENCY_ORIGIN", reference_kind="source-type"))
            continue
        artifact = candidates[0]
        identity = tuple(artifact[key] for key in IDENTITY)
        visible = [node for node in direct if compilation["purpose"] == "test" or node["scope"] in {"compile", "provided"}]
        declared = [node for node in visible if tuple(node[key] for key in IDENTITY) == identity]
        if declared:
            results.append(dict(detail, artifact=artifact, basis="direct", provider=declared[0]))
            continue
        providers = [(node, source) for provider, api, source in rules if api == identity
                     for node in visible if tuple(node[key] for key in IDENTITY) == provider]
        if providers:
            results.append(dict(detail, artifact=artifact, basis="capability-api", provider=providers[0][0], source=providers[0][1]))
        else:
            known = any(api[:2] == identity[:2] and api[3:] == identity[3:] and
                        any(tuple(node[key] for key in IDENTITY[:2]) == provider[:2] and
                            tuple(node[key] for key in IDENTITY[3:]) == provider[3:] for node in visible)
                        for provider, api, _ in rules)
            diagnostics.append(dict(detail, artifact=artifact,
                                    code="SG_DEPENDENCY_CAPABILITY_UNCALIBRATED" if known else "SG_UNDECLARED_LIBRARY"))
    return results, diagnostics
