"""真实 Boot 4 HTTP 合同机制；只运行显式仓库外隔离工程，不提供产品验收。"""

import argparse
import base64
import json
import os
import pathlib
import re
import subprocess
import tempfile
import urllib.parse
import uuid
import xml.etree.ElementTree as ET

import calibrate_maven
import calibrate_maven_source
from calibrate_source import HERE, ProbeFailure, digest, execute, require


def baseline():
    lock = read_json(HERE / "http-contract.lock.json")
    require(lock.get("schema") == 1 and lock["boot_version"].startswith("4.0.") and
            lock["spring_version"].startswith("7.0.") and lock["springdoc_version"].startswith("3.0."),
            "SG_HTTP_CALIBRATION", "当前适配限定已批准 Boot 4.0 配套代际，其他组合须先校准")
    return lock


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "SG_HTTP_INPUT", "JSON 输入存在重复字段")
            result[key] = value
        return result
    require(path.is_file() and not path.is_symlink(), "SG_HTTP_INPUT", "缺少普通 JSON 输入文件")
    result = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    require(isinstance(result, dict), "SG_HTTP_INPUT", "JSON 输入根必须为对象，不能用堆栈异常代替结构化拒绝")
    return result


def configuration():
    return {name: digest(HERE / name) for name in
            ("http-contract.lock.json", "calibrate_http.py", "HttpInventory.java", "HttpValidation.java", "DomainForgeUnassignedProbe.java")}


def dependency(group, artifact, version=None):
    fixed = "<version>" + version + "</version>" if version else ""
    return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>" + fixed + "</dependency>"


def framework_pom():
    lock = baseline()
    management = dependency("org.springframework.boot", "spring-boot-dependencies", lock["boot_version"])
    management = management.replace("</dependency>", "<type>pom</type><scope>import</scope></dependency>")
    return "<dependencyManagement><dependencies>" + management + "</dependencies></dependencyManagement><dependencies>" + \
           dependency("org.springframework.boot", "spring-boot-starter-webmvc") + \
           dependency("org.springdoc", "springdoc-openapi-starter-webmvc-api", lock["springdoc_version"]) + "</dependencies>"


def prepare(args):
    """固定成熟库由真实 Maven 准备；工具与应用分开解析，避免污染实际应用类路径。"""
    target = pathlib.Path(args.prepare)
    require(target.is_dir() and not target.is_symlink() and not any(target.iterdir()) and
            not target.resolve().is_relative_to(HERE.parents[1]), "SG_HTTP_TOOLS", "准备需要仓库外专用空目录")
    home, _ = calibrate_maven.runtime(args.maven_home)
    repository = pathlib.Path(args.repository or "")
    require(args.repository and repository.is_dir() and not repository.is_symlink(), "SG_HTTP_TOOLS", "准备需要专用 Maven 仓库")
    lock = baseline()
    settings = target / "settings.xml"
    settings.write_text('<settings><mirrors><mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf>'
                        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>', encoding="utf-8")
    libraries = "<dependencies>" + "".join(dependency(**lock[key]) for key in ("validator", "diff")) + "</dependencies>"
    for name, body in (("tools", libraries), ("framework", framework_pom())):
        project = target / name
        project.mkdir()
        pom = project / "pom.xml"
        pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                       '<groupId>probe</groupId><artifactId>http-' + name + '</artifactId><version>1.0.0</version>' + body + '</project>', encoding="utf-8")
        command = [str(home / "bin/mvn"), "-B", "-ntp", "-s", str(settings), "-gs", str(settings),
                   "-Dmaven.repo.local=" + str(repository.resolve()), "-f", str(pom),
                   "org.apache.maven.plugins:maven-dependency-plugin:" + lock["dependency_plugin_version"] + ":copy-dependencies",
                   "-DincludeScope=runtime", "-DoutputDirectory=" + str(project / "dependencies")]
        log = project / "prepare.log"
        with log.open("w", encoding="utf-8") as output:
            try:
                process = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, text=True, timeout=600)
            except subprocess.TimeoutExpired as error:
                raise ProbeFailure("SG_HTTP_TOOLS", "原生准备超时；保留日志 " + str(log)) from error
        require(process.returncode == 0, "SG_HTTP_TOOLS", "真实 Maven HTTP 依赖准备失败；保留日志，不称为已准备")
    artifacts = {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}
    require(artifacts, "SG_HTTP_TOOLS", "未解析出实际工具与框架")
    manifest = {"schema": 1, "lock_sha256": digest(HERE / "http-contract.lock.json"), "artifacts": artifacts}
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    args.http_tools = str(target)
    return environment(args)


def environment(args):
    target = pathlib.Path(args.http_tools or "")
    require(args.http_tools and target.is_dir() and not target.is_symlink() and (target / "manifest.json").is_file(),
            "SG_HTTP_TOOLS", "缺少实际准备的 HTTP 合同工具，不能跳过")
    manifest = read_json(target / "manifest.json")
    require(manifest.get("schema") == 1 and manifest.get("lock_sha256") == digest(HERE / "http-contract.lock.json") and
            isinstance(manifest.get("artifacts"), dict) and manifest["artifacts"], "SG_HTTP_TOOLS", "工具资料不属于当前固定基线")
    require(not any(path.is_symlink() for path in target.rglob("*")), "SG_HTTP_TOOLS", "工具目录不接受符号链接")
    observed = {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}
    require(observed == manifest["artifacts"], "SG_HTTP_TOOLS", "实际 JAR 缺失、额外或改变")
    lock = baseline()
    for key in ("validator", "diff"):
        item = lock[key]
        require("tools/dependencies/" + item["artifact"] + "-" + item["version"] + ".jar" in observed,
                "SG_HTTP_TOOLS", "缺少固定成熟校验库")
    return {"ok": True, "proof_scope": "isolated-http-environment-only", "http_tools": str(target.resolve()),
            "artifacts": observed, "maven": calibrate_maven.environment(args)["environment"], "diagnostics": []}


def scenarios(path):
    definition = read_json(path)
    require(isinstance(definition, dict) and set(definition) == {"schema", "version", "application", "package", "endpoints"} and
            definition["schema"] == 1 and isinstance(definition["endpoints"], list) and definition["endpoints"],
            "SG_HTTP_INPUT", "需要独立场景与精确入口清单，不能空执行")
    require(isinstance(definition["version"], str) and definition["version"] and "SNAPSHOT" not in definition["version"],
            "SG_HTTP_INPUT", "合同须提供明确非快照版本")
    for field in ("application", "package"):
        require(isinstance(definition[field], str) and re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", definition[field]),
                "SG_HTTP_INPUT", "应用类与包必须为明确名称")
    identities, ids, result = set(), set(), []
    for endpoint in definition["endpoints"]:
        require(isinstance(endpoint, dict) and set(endpoint) == {"path", "method", "protocol", "scenarios"} and
                isinstance(endpoint["path"], str) and endpoint["path"].startswith("/") and
                not endpoint["path"].startswith("//") and "?" not in endpoint["path"] and "#" not in endpoint["path"] and
                endpoint["protocol"] in ("json", "binary", "stream", "operations") and
                isinstance(endpoint["scenarios"], list) and endpoint["scenarios"],
                "SG_HTTP_INPUT", "入口须明确协议与必要场景")
        identity = (endpoint["path"], endpoint["method"])
        require(identity not in identities, "SG_HTTP_INPUT", "入口声明重复")
        identities.add(identity)
        for item in endpoint["scenarios"]:
            required = {"id", "request", "status", "code", "data"} if endpoint["protocol"] == "json" else \
                       {"id", "request", "status", "content_type", "body_base64"}
            require(isinstance(item, dict) and set(item) in (required, required | {"valid_request"}) and
                    isinstance(item["id"], str) and item["id"] and item["id"] not in ids and item["id"] != "__openapi__" and
                    type(item["status"]) is int and 100 <= item["status"] <= 599,
                    "SG_HTTP_INPUT", "场景必须唯一、完整并提供独立状态期望")
            request = item["request"]
            require(isinstance(request, dict) and set(request) == {"path", "method", "headers", "body"} and
                    isinstance(request["path"], str) and request["path"].startswith("/") and not request["path"].startswith("//") and
                    not urllib.parse.urlsplit(request["path"]).fragment and request["method"] == endpoint["method"] and
                    request["method"] in {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE"} and
                    isinstance(request["headers"], dict) and all(isinstance(k, str) and isinstance(v, str) for k, v in request["headers"].items()) and
                    (request["body"] is None or isinstance(request["body"], str)) and type(item.get("valid_request", True)) is bool,
                    "SG_HTTP_INPUT", "请求须显式定义方法、回环相对路径、头与原始正文")
            if endpoint["protocol"] == "json":
                require(isinstance(item["code"], str) and item["code"] and
                        not (item["status"] >= 400 and item["code"] in {"SUCCESS", "ACCEPTED"}),
                        "SG_HTTP_INPUT", "场景必须提供符合 HTTP 类别的稳定字符串结果标识")
            else:
                require(isinstance(item["content_type"], str) and isinstance(item["body_base64"], str),
                        "SG_HTTP_INPUT", "非 JSON 协议须提供媒体类型及原始字节期望")
                base64.b64decode(item["body_base64"], validate=True)
            ids.add(item["id"])
            result.append(dict(item, protocol=endpoint["protocol"], endpoint=list(identity), valid_request=item.get("valid_request", True)))
        require(any(item.get("valid_request", True) for item in endpoint["scenarios"]),
                "SG_HTTP_SCENARIO_COVERAGE", "每个入口至少提供一个完整请求合同验证，不能全部标为无效请求绕过")
    return definition, result


def capture(args, build, definition, cases, temporary, observer="HttpInventory"):
    compilation = next(item for item in build["compilations"] if item["purpose"] == "production")
    output = pathlib.Path(compilation["output"])
    require((output / (definition["application"].replace(".", "/") + ".class")).is_file(),
            "SG_HTTP_ENTRY_SCOPE", "应用入口不属于同次实际生产产物")
    lock = baseline()
    dependencies = {(item["group"], item["artifact"]): item["version"] for item in compilation["dependencies"]}
    for group, artifact, version in (("org.springframework.boot", "spring-boot", lock["boot_version"]),
                                     ("org.springframework", "spring-webmvc", lock["spring_version"]),
                                     ("org.springdoc", "springdoc-openapi-starter-webmvc-api", lock["springdoc_version"])):
        require(dependencies.get((group, artifact)) == version, "SG_HTTP_CALIBRATION", "实际 HTTP 框架组合尚未校准")
    classes = temporary / "observer"
    classes.mkdir()
    classpath = os.pathsep.join(compilation["classpath"])
    require(observer in ("HttpInventory", "SaAccessInventory"), "SG_HTTP_TOOL_FAILURE", "只运行固定的内部观察器")
    sources = [str(HERE / "HttpInventory.java")]
    probe_arguments = []
    if observer != "HttpInventory":
        sources.append(str(HERE / (observer + ".java")))
    if observer == "SaAccessInventory" and getattr(args, "default_deny_subject", None):
        name = definition["package"] + ".DomainForgeUnassignedProbe"
        require(not (output / (name.replace(".", "/") + ".class")).exists(), "SG_HTTP_CALIBRATION", "工具探针类型与应用冲突，不覆盖")
        template = temporary / "DomainForgeUnassignedProbe.java"
        template.write_text((HERE / template.name).read_text(encoding="utf-8").replace(
            "package org.domainforge.gate;", "package " + definition["package"] + ";", 1), encoding="utf-8")
        sources.append(str(template))
        probe_path = "/__domain_forge_unassigned_" + uuid.uuid4().hex
        requests = ET.Element("scenarios")
        for identifier in ("anonymous", "authenticated"):
            node = ET.SubElement(requests, "scenario", id="__default_deny_" + identifier, path=probe_path, method="GET")
            if identifier == "authenticated":
                ET.SubElement(node, "header", name="Authorization", value=args.default_deny_subject)
        request_path = temporary / "default-deny-requests.xml"
        ET.ElementTree(requests).write(request_path, encoding="utf-8", xml_declaration=True)
        probe_arguments = [name, str(request_path), probe_path]
    compiled = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(classes), *sources])
    (pathlib.Path(args.project) / "target/http-observer-compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
    require(compiled.returncode == 0, "SG_HTTP_TOOL_FAILURE", "真实 HTTP 观察器编译失败，不能当作规则反例")
    requests = ET.Element("scenarios")
    generation = {"id": "__openapi__", "request": {"path": "/v3/api-docs", "method": "GET", "headers": {}, "body": None}}
    for case in [generation, *cases]:
        request = case["request"]
        require(all(name.lower() != "x-domain-forge-scenario" for name in request["headers"]),
                "SG_HTTP_INPUT", "独立请求不得伪装内部观察标识")
        node = ET.SubElement(requests, "scenario", id=case["id"], path=request["path"], method=request["method"])
        node.text = base64.b64encode((request["body"] or "").encode("utf-8")).decode("ascii")
        for name, value in request["headers"].items():
            ET.SubElement(node, "header", name=name, value=value)
    source = temporary / "requests.xml"
    ET.ElementTree(requests).write(source, encoding="utf-8", xml_declaration=True)
    observed = temporary / "observed.xml"
    runtime = execute(["java", "-cp", os.pathsep.join([str(classes), classpath]), observer, definition["application"], str(source), str(observed), *probe_arguments])
    (pathlib.Path(args.project) / "target/http-runtime.log").write_text(runtime.stdout + runtime.stderr, encoding="utf-8")
    require(runtime.returncode == 0 and observed.is_file(), "SG_HTTP_RUNTIME", "真实应用启动、HTTP 或采集失败；保留日志")
    (pathlib.Path(args.project) / "target/http-observation.xml").write_bytes(observed.read_bytes())
    return ET.parse(observed).getroot(), output


def observations(document, output, definition, cases):
    require(document.get("boot") == baseline()["boot_version"] and document.get("spring") == baseline()["spring_version"],
            "SG_HTTP_CALIBRATION", "实际启动框架版本不属于已校准组合")
    require(not document.findall("unscanned"), "SG_HTTP_ROUTE_SCOPE",
            "实际注册包含尚未校准的非注解路由：" + str([dict(item.attrib) for item in document.findall("unscanned")]))
    registered = []
    for node in document.findall("registered"):
        item = dict(node.attrib)
        origin = urllib.parse.urlsplit(item["origin"])
        require(origin.scheme == "file" and not (origin.netloc or origin.query or origin.fragment),
                "SG_HTTP_ENTRY_SCOPE", "处理器缺少明确文件来源：" + item["owner"])
        if pathlib.Path(urllib.parse.unquote(origin.path)).resolve() == output.resolve():
            require(item["owner"].startswith(definition["package"] + "."), "SG_HTTP_ENTRY_SCOPE", "工程内入口不能靠缩小声明包隐藏")
            registered.append(item)
        else:
            require(item["owner"].startswith("org.springdoc.") or item["owner"].startswith("org.springframework.boot.webmvc."),
                    "SG_HTTP_ENTRY_SCOPE", "外部注册处理器尚未分类，不能悄悄漏检：" + item["owner"])
    responses = document.findall("response")
    by_id = {item.get("scenario"): item for item in responses}
    require(len(by_id) == len(responses) and set(by_id) == {"__openapi__", *[case["id"] for case in cases]},
            "SG_HTTP_SCENARIO_COVERAGE", "实际交互缺失、额外或重复")
    generated = by_id.pop("__openapi__")
    require(generated.get("status") == "200", "SG_HTTP_GENERATION", "真实生成入口未成功")
    openapi = json.loads(base64.b64decode("".join(generated.itertext()), validate=True))
    interactions = []
    for case in cases:
        node = by_id[case["id"]]
        require(node.get("dispatched-pattern") == case["endpoint"][0] and
                any(item["path"] == case["endpoint"][0] and item["method"] == case["endpoint"][1] and
                    item["owner"] == node.get("dispatched-owner") and item["handler"] == node.get("dispatched-handler")
                    for item in registered), "SG_HTTP_DISPATCH", "场景必须由声明的实际入口处理，不能请求另一入口或缺少分派观察")
        headers = {}
        for header in node.findall("header"):
            headers.setdefault(header.get("name"), []).append(header.get("value"))
        raw = base64.b64decode("".join(node.itertext()), validate=True)
        body = raw.decode("utf-8", errors="replace")
        if any("application/json" in value.lower() for value in headers.get("content-type", [])):
            try:
                body = json.loads(raw)
            except (ValueError, UnicodeError):
                pass  # 保留畸形原文，交给成熟结构检查及外壳检查拒绝。
        interactions.append(dict(scenario=case["id"], request=case["request"], valid_request=case["valid_request"],
                                 status=int(node.get("status")), headers=headers, body=body, body_base64=base64.b64encode(raw).decode("ascii"),
                                 dispatch={key: node.get("dispatched-" + key) for key in ("pattern", "owner", "handler")}))
    return registered, openapi, interactions


def local_spec(specification):
    """此适配只校准自包含发布产物；外部引用先明确打包，不交给解析库隐式联网。"""
    if isinstance(specification, dict):
        for key, value in specification.items():
            if key == "$ref":
                require(isinstance(value, str) and value.startswith("#/"), "SG_HTTP_SPEC_SCOPE", "非自包含合同尚未校准")
            local_spec(value)
    elif isinstance(specification, list):
        for item in specification:
            local_spec(item)


def validation(args, openapi, interactions, temporary):
    current, actual, result = [temporary / name for name in ("openapi.json", "interactions.json", "validation.json")]
    current.write_text(json.dumps(openapi), encoding="utf-8")
    actual.write_text(json.dumps(interactions), encoding="utf-8")
    tool_root = pathlib.Path(args.http_tools)
    classpath = os.pathsep.join(str(path) for path in sorted((tool_root / "tools/dependencies").glob("*.jar")))
    classes = temporary / "validator"
    classes.mkdir()
    compiled = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(classes), str(HERE / "HttpValidation.java")])
    log = pathlib.Path(args.project) / "target/http-validation.log"
    log.write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
    require(compiled.returncode == 0, "SG_HTTP_TOOL_FAILURE", "成熟校验库适配编译失败，不能当作目标反例")
    process = execute(["java", "-cp", os.pathsep.join([str(classes), classpath]), "HttpValidation",
                       str(current), str(pathlib.Path(args.baseline).resolve()), str(actual), str(result)])
    log.write_text(process.stdout + process.stderr, encoding="utf-8")
    require(process.returncode == 0 and result.is_file(), "SG_HTTP_TOOL_FAILURE", "成熟合同验证器执行失败，保留原生日志")
    return read_json(result)


def coverage(registered, openapi, definition):
    actual = [(item["path"], item["method"]) for item in registered]
    require(len(actual) == len(set(actual)) and all(item["params"] == "[]" and item["headers"] == "[]" for item in registered),
            "SG_HTTP_ROUTE_SCOPE", "同路径条件分流尚未校准，不能按单一入口报通过")
    generated = {(path, method.upper()) for path, path_item in openapi.get("paths", {}).items() for method in path_item
                 if method in {"get", "post", "put", "delete", "patch", "head", "options", "trace"}}
    declared = {(item["path"], item["method"]) for item in definition["endpoints"]}
    errors = []
    for code, expected in (("SG_HTTP_GENERATION_COVERAGE", generated), ("SG_HTTP_SCENARIO_COVERAGE", declared)):
        if set(actual) != expected:
            errors.append({"code": code, "missing": sorted(set(actual) - expected), "extra": sorted(expected - set(actual))})
    return errors


def semantics(interactions, cases):
    errors = []
    for actual, expected in zip(interactions, cases):
        body = actual["body"]
        if expected["protocol"] == "json":
            if not (isinstance(body, dict) and set(body) == {"code", "message", "data"} and
                    isinstance(body["code"], str) and body["code"] and isinstance(body["message"], str)):
                errors.append({"code": "SG_HTTP_ENVELOPE", "scenario": expected["id"]})
            elif body["code"] != expected["code"] or body["data"] != expected["data"]:
                errors.append({"code": "SG_HTTP_SEMANTICS", "scenario": expected["id"], "expected_code": expected["code"]})
        elif [value.split(";")[0] for value in actual["headers"].get("content-type", [])] != [expected["content_type"]] or \
                actual["body_base64"] != expected["body_base64"]:
            errors.append({"code": "SG_HTTP_PROTOCOL", "scenario": expected["id"]})
        if actual["status"] != expected["status"]:
            errors.append({"code": "SG_HTTP_SEMANTICS", "scenario": expected["id"], "expected_status": expected["status"], "actual_status": actual["status"]})
    return errors


def probe(args, observer="HttpInventory"):
    before = configuration()
    rules = pathlib.Path(args.scenarios)
    released = pathlib.Path(args.baseline)
    require(released.is_file() and not released.is_symlink() and re.fullmatch(r"[a-f0-9]{64}", args.baseline_sha256 or "") and
            digest(released) == args.baseline_sha256, "SG_HTTP_BASELINE", "已发布合同缺失或不符独立固定摘要，不重置基线")
    rule_hash = digest(rules)
    definition, cases = scenarios(rules)
    previous = read_json(released)
    require(previous.get("openapi") == baseline()["openapi_version"], "SG_HTTP_SPEC_SCOPE", "已发布合同版本超出校准范围")
    local_spec(previous)
    env = environment(args)
    build = calibrate_maven.probe(args)
    with tempfile.TemporaryDirectory(prefix="domain-forge-http-") as directory:
        temporary = pathlib.Path(directory)
        document, output = capture(args, build, definition, cases, temporary, observer)
        registered, openapi, interactions = observations(document, output, definition, cases)
        require(openapi.get("openapi") == baseline()["openapi_version"], "SG_HTTP_SPEC_SCOPE", "实际 OpenAPI 版本不属于固定校准范围")
        require(openapi.get("info", {}).get("version") == definition["version"],
                "SG_HTTP_VERSION", "实际生成合同未绑定独立声明的交付版本")
        local_spec(openapi)
        errors = coverage(registered, openapi, definition)
        checked = validation(args, openapi, interactions, temporary)
    issues = [item for item in checked["messages"] if item["level"] == "ERROR"]
    errors.extend({"code": "SG_HTTP_SCHEMA", **item} for item in issues)
    if not checked["compatible"]:
        errors.append({"code": "SG_HTTP_BREAKING", "message": "当前实现与生成物一致不消除已发布合同的不兼容"})
    errors.extend(semantics(interactions, cases))
    calibrate_maven_source.unchanged_build(build, pathlib.Path(args.project).resolve())
    require(before == configuration() and rule_hash == digest(rules) and args.baseline_sha256 == digest(released) and env == environment(args),
            "SG_INPUT_CHANGED", "执行期间场景、发布合同、框架或工具改变")
    return {"ok": not errors, "proof_scope": "isolated-boot4-registered-http-contract-only", "build": build,
            "runtime_observation": dict(document.attrib),
            "registered": registered, "openapi": openapi, "interactions": interactions, "compatibility": checked,
            "runtime_schema_checked": not issues, "released_compatibility_checked": checked["compatible"],
            "product_verified": False, "semantic_review_proven": False, "human_acceptance": False,
            "configuration": before, "environment": env, "scenarios": {"path": str(rules.resolve()), "sha256": rule_hash},
            "contract_version": definition["version"],
            "baseline": {"path": str(released.resolve()), "sha256": args.baseline_sha256}, "diagnostics": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--prepare")
    operation.add_argument("--environment", action="store_true")
    parser.add_argument("--scenarios")
    parser.add_argument("--baseline")
    parser.add_argument("--baseline-sha256")
    parser.add_argument("--http-tools", default=os.environ.get("DOMAIN_FORGE_GATE_HTTP_TOOLS"))
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    args = parser.parse_args()
    if args.project and not all((args.scenarios, args.baseline, args.baseline_sha256)):
        parser.error("隔离项目必须提供独立场景、已发布合同及固定摘要")
    try:
        result = prepare(args) if args.prepare else {"ok": True, "environment": environment(args), "diagnostics": []} if args.environment else probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_HTTP_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
