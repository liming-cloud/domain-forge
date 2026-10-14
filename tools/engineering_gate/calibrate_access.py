"""Sa-Token 实际 Header-only 机制校准；不证明完整授权策略、Redis 或产品 IAM。"""

import argparse
import json
import os
import pathlib
import subprocess
import urllib.parse
import xml.etree.ElementTree as ET

import calibrate_http
import calibrate_maven
import sa_policy_contracts
from calibrate_source import HERE, ProbeFailure, digest, require


def baseline():
    lock = calibrate_http.read_json(HERE / "sa-access.lock.json")
    require(lock.get("schema") == 1 and lock.get("starter") == "sa-token-spring-boot4-starter" and
            lock.get("sa_token_version") == "1.46.0", "SG_ACCESS_CALIBRATION", "实际适配必须属于固定 Boot 4 Sa-Token 校准")
    return lock


def configuration():
    return {name: digest(HERE / name) for name in ("sa-access.lock.json", "calibrate_access.py", "SaAccessInventory.java", "sa_policy_contracts.py", "DomainForgeUnassignedProbe.java")}


def prepare(args):
    target = pathlib.Path(args.prepare)
    require(target.is_dir() and not target.is_symlink() and not any(target.iterdir()) and
            not target.resolve().is_relative_to(HERE.parents[1]), "SG_ACCESS_TOOLS", "准备需要仓库外专用空目录")
    home, _ = calibrate_maven.runtime(args.maven_home)
    repository = pathlib.Path(args.repository or "")
    require(args.repository and repository.is_dir() and not repository.is_symlink(), "SG_ACCESS_TOOLS", "准备需要专用 Maven 仓库")
    lock = baseline()
    settings = target / "settings.xml"
    settings.write_text('<settings><mirrors><mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf>'
                        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>', encoding="utf-8")
    pom = target / "pom.xml"
    body = calibrate_http.framework_pom().removesuffix("</dependencies>") + \
           calibrate_http.dependency("cn.dev33", lock["starter"], lock["sa_token_version"]) + "</dependencies>"
    pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                   '<groupId>probe</groupId><artifactId>sa-access-environment</artifactId><version>1.0.0</version>' + body + '</project>', encoding="utf-8")
    command = [str(home / "bin/mvn"), "-B", "-ntp", "-s", str(settings), "-gs", str(settings),
               "-Dmaven.repo.local=" + str(repository.resolve()), "-f", str(pom),
               "org.apache.maven.plugins:maven-dependency-plugin:" + lock["dependency_plugin_version"] + ":copy-dependencies",
               "-DincludeScope=runtime", "-DoutputDirectory=" + str(target / "dependencies")]
    log = target / "prepare.log"
    with log.open("w", encoding="utf-8") as output:
        try:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, text=True, timeout=30)
        except subprocess.TimeoutExpired as error:
            raise ProbeFailure("SG_ACCESS_TOOLS", "原生准备超时，保留日志 " + str(log)) from error
    require(result.returncode == 0, "SG_ACCESS_TOOLS", "实际 Sa-Token 依赖准备失败，保留日志")
    manifest = {"schema": 1, "lock_sha256": digest(HERE / "sa-access.lock.json"),
                "artifacts": {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}}
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    args.access_tools = str(target)
    return environment(args)


def environment(args):
    target = pathlib.Path(args.access_tools or "")
    require(args.access_tools and target.is_dir() and not target.is_symlink(), "SG_ACCESS_TOOLS", "缺少实际 Sa-Token 校准依赖")
    manifest = calibrate_http.read_json(target / "manifest.json")
    require(manifest.get("schema") == 1 and manifest.get("lock_sha256") == digest(HERE / "sa-access.lock.json") and
            isinstance(manifest.get("artifacts"), dict) and manifest["artifacts"], "SG_ACCESS_TOOLS", "资料不属于当前 Sa-Token 基线")
    require(not any(path.is_symlink() for path in target.rglob("*")), "SG_ACCESS_TOOLS", "校准依赖不接受符号链接")
    artifacts = {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}
    lock = baseline()
    require(artifacts == manifest["artifacts"] and "dependencies/" + lock["starter"] + "-" + lock["sa_token_version"] + ".jar" in artifacts,
            "SG_ACCESS_TOOLS", "实际依赖缺失、增加或改变")
    return {"ok": True, "proof_scope": "isolated-sa-token-environment-only", "access_tools": str(target.resolve()),
            "artifacts": artifacts, "http": calibrate_http.environment(args), "diagnostics": []}


def transport_cases(path, cases):
    """独立场景必须覆盖约定渠道，不能删掉拒绝场景后让配置检查独自报通过。"""
    declaration = calibrate_http.read_json(path)
    roles = {"bearer", "absent", "wrong-prefix", "invalid", "query", "form", "cookie"}
    require(set(declaration) == {"schema", "transport"} and declaration["schema"] == 1 and
            isinstance(declaration["transport"], dict) and set(declaration["transport"]) == roles and
            len(set(declaration["transport"].values())) == len(roles), "SG_ACCESS_SCENARIOS", "必须明确全部凭证渠道的独立场景")
    by_id = {item["id"]: item for item in cases}
    require(all(value in by_id for value in declaration["transport"].values()), "SG_ACCESS_SCENARIOS", "引用的访问场景未执行")
    selected = {role: by_id[identifier] for role, identifier in declaration["transport"].items()}
    require(200 <= selected["bearer"]["status"] < 300 and
            all(case["status"] == 401 for role, case in selected.items() if role != "bearer"),
            "SG_ACCESS_SCENARIOS", "代表性受保护入口必须区分有效 Header 与其他渠道的未认证拒绝")
    header = {key.lower(): value for key, value in selected["bearer"]["request"]["headers"].items()}.get("authorization", "")
    require(header.startswith("Bearer ") and header.removeprefix("Bearer "), "SG_ACCESS_SCENARIOS", "正例必须使用真实有效 Bearer Header")
    token = header.removeprefix("Bearer ")
    reference_path = urllib.parse.urlsplit(selected["bearer"]["request"]["path"]).path
    for role, case in selected.items():
        request = case["request"]
        url = urllib.parse.urlsplit(request["path"])
        headers = {key.lower(): value for key, value in request["headers"].items()}
        require(len(headers) == len(request["headers"]) and url.path == reference_path and
                case["endpoint"] == [reference_path, request["method"]], "SG_ACCESS_SCENARIOS", "渠道须指向相同受保护路径且头字段不含大小写重复")
        auth = headers.get("authorization")
        expected_auth = header if role == "bearer" else token if role == "wrong-prefix" else None
        if role == "invalid":
            require(auth and auth.startswith("Bearer ") and auth != header, "SG_ACCESS_SCENARIOS", "无效令牌场景不能重用有效令牌或缺少 Header")
        else:
            require(auth == expected_auth, "SG_ACCESS_SCENARIOS", "渠道标签与实际 Authorization Header 不符")
        query = urllib.parse.parse_qs(url.query, keep_blank_values=True, strict_parsing=True)
        require(query == ({"Authorization": [header]} if role == "query" else {}),
                "SG_ACCESS_SCENARIOS", "查询参数反例必须真实提交有效令牌，不能换成空请求")
        require(headers.get("cookie") == ("Authorization=" + token if role == "cookie" else None),
                "SG_ACCESS_SCENARIOS", "Cookie 反例须实际携带同一有效令牌")
        if role == "form":
            require(request["method"] == "POST" and headers.get("content-type", "").split(";")[0] == "application/x-www-form-urlencoded" and
                    urllib.parse.parse_qs(request["body"] or "", keep_blank_values=True, strict_parsing=True) == {"Authorization": [header]},
                    "SG_ACCESS_SCENARIOS", "表单反例须真实提交有效令牌，不以缺少令牌的请求替代")
        else:
            require(not request["body"], "SG_ACCESS_SCENARIOS", "其他渠道场景不同时加入表单凭证")
    for endpoint in {tuple(case["endpoint"]) for case in selected.values()}:
        require(any(tuple(case["endpoint"]) == endpoint and 200 <= case["status"] < 300 and
                    {key.lower(): value for key, value in case["request"]["headers"].items()}.get("authorization") == header
                    for case in cases), "SG_ACCESS_SCENARIOS", "每个受保护请求方法至少有一个有效 Header 成功正例，不能一律拒绝")
    return selected


def probe(args):
    before, env = configuration(), environment(args)
    path = pathlib.Path(args.access)
    declared_sha = digest(path)
    _, cases = calibrate_http.scenarios(pathlib.Path(args.scenarios))
    selected = transport_cases(path, cases)
    policy_path = pathlib.Path(args.policies) if args.policies else None
    policy_sha = digest(policy_path) if policy_path else None
    policy_definition = sa_policy_contracts.definition(policy_path, cases) if policy_path else None
    args.default_deny_subject = sa_policy_contracts.credential(selected["bearer"]) if policy_path else None
    report = calibrate_http.probe(args, "SaAccessInventory")
    policies = []
    if policy_definition is not None:
        errors, policies = sa_policy_contracts.assess(policy_definition, report, cases,
                                                     pathlib.Path(args.project) / "target/http-observation.xml")
        report["diagnostics"].extend(errors)
    expected = {"sa-token-name": "Authorization", "sa-token-prefix": "Bearer", "sa-read-header": "true",
                "sa-read-body": "false", "sa-read-cookie": "false"}
    if any(report["runtime_observation"].get(key) != value for key, value in expected.items()):
        report["diagnostics"].append({"code": "SG_ACCESS_TRANSPORT", "message": "实际登录配置不符合唯一 Header 合同"})
    compilation = next(item for item in report["build"]["compilations"] if item["purpose"] == "production")
    dependencies = {(item["group"], item["artifact"]): item["version"] for item in compilation["dependencies"]}
    lock = baseline()
    require(all(dependencies.get(("cn.dev33", name)) == lock["sa_token_version"] for name in (lock["starter"], "sa-token-core")),
            "SG_ACCESS_CALIBRATION", "实际 Sa-Token 版本或 Boot 适配尚未校准")
    require(all(version == lock["sa_token_version"] for (group, _), version in dependencies.items() if group == "cn.dev33"),
            "SG_ACCESS_CALIBRATION", "Sa-Token 模块混用其他版本不属于当前校准组合")
    require(before == configuration() and declared_sha == digest(path) and env == environment(args) and
            (policy_path is None or policy_sha == digest(policy_path)),
            "SG_INPUT_CHANGED", "执行期间访问规则或工具改变")
    # 公开证据采用字段白名单，不复制认证头、Cookie、查询／表单值或原始响应。
    return {"ok": not report["diagnostics"], "proof_scope": "isolated-sa-token-native-policy-only" if policy_path else "isolated-sa-token-header-transport-only",
            "build": report["build"], "registered": report["registered"], "runtime_observation": report["runtime_observation"],
            "interactions": [{key: item[key] for key in ("scenario", "status", "dispatch")} for item in report["interactions"]],
            "runtime_schema_checked": report["runtime_schema_checked"], "released_compatibility_checked": report["released_compatibility_checked"],
            "configuration": report["configuration"], "access_configuration": before, "environment": env,
            "scenarios": report["scenarios"], "baseline": report["baseline"], "contract_version": report["contract_version"],
            "access_scenarios": {"path": str(path.resolve()), "sha256": declared_sha, "roles": sorted(selected)},
            "diagnostics": [{key: item[key] for key in ("code", "scenario", "key", "level", "expected_status", "actual_status", "path", "method") if key in item}
                            for item in report["diagnostics"]],
            "policy_definition": {"path": str(policy_path.resolve()), "sha256": policy_sha} if policy_path else None,
            "annotation_policy_checked": policy_path is not None and not report["diagnostics"], "effective_policies": policies,
            "default_deny_checked": policy_path is not None and not report["diagnostics"],
            "access_policy_verified": False, "redis_verified": False, "product_verified": False,
            "semantic_review_proven": False, "human_acceptance": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--prepare")
    operation.add_argument("--environment", action="store_true")
    for name in ("scenarios", "baseline", "baseline-sha256", "access", "policies"):
        parser.add_argument("--" + name)
    parser.add_argument("--http-tools", default=os.environ.get("DOMAIN_FORGE_GATE_HTTP_TOOLS"))
    parser.add_argument("--access-tools", default=os.environ.get("DOMAIN_FORGE_GATE_ACCESS_TOOLS"))
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    args = parser.parse_args()
    if args.project and not all((args.scenarios, args.baseline, args.baseline_sha256, args.access)):
        parser.error("隔离项目必须提供 HTTP 场景、发布合同与摘要、独立访问场景映射")
    try:
        result = prepare(args) if args.prepare else {"ok": True, "environment": environment(args), "diagnostics": []} if args.environment else probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_ACCESS_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
