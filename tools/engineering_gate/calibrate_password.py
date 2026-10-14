"""实际 Argon2id Bean 与独立成熟校验器；只使用合成密码，不实现产品认证。"""

import argparse
import base64
import json
import os
import pathlib
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

import calibrate_http
import calibrate_maven
import calibrate_maven_source
from calibrate_source import HERE, ProbeFailure, digest, execute, require, toolchain


def baseline():
    lock = calibrate_http.read_json(HERE / "password.lock.json")
    require(lock.get("schema") == 1 and lock.get("crypto_version") == "7.0.7" and
            lock.get("bouncycastle_version") == "1.86", "SG_PASSWORD_CALIBRATION", "需要固定且实际校准的密码实现")
    return lock


def configuration():
    return {name: digest(HERE / name) for name in
            ("password.lock.json", "calibrate_password.py", "PasswordInventory.java", "HttpInventory.java",
             "calibrate_http.py", "http-contract.lock.json", "calibrate_maven.py", "MavenScopeSpy.java",
             "maven-scope.lock.json", "calibrate_maven_source.py")}


def prepare(args):
    target = pathlib.Path(args.prepare)
    require(target.is_dir() and not target.is_symlink() and not any(target.iterdir()) and
            not target.resolve().is_relative_to(HERE.parents[1]), "SG_PASSWORD_TOOLS", "准备需要仓库外专用空目录")
    home, _ = calibrate_maven.runtime(args.maven_home)
    repository = pathlib.Path(args.repository or "")
    require(args.repository and repository.is_dir() and not repository.is_symlink(), "SG_PASSWORD_TOOLS", "准备需要专用 Maven 仓库")
    lock = baseline()
    settings = target / "settings.xml"
    settings.write_text('<settings><mirrors><mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf>'
                        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>', encoding="utf-8")
    pom = target / "pom.xml"
    dependencies = calibrate_http.dependency("org.springframework.security", "spring-security-crypto", lock["crypto_version"]) + \
                   calibrate_http.dependency("org.bouncycastle", "bcprov-jdk18on", lock["bouncycastle_version"])
    pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                   '<groupId>probe</groupId><artifactId>password-environment</artifactId><version>1.0.0</version>'
                   '<dependencies>' + dependencies + '</dependencies></project>', encoding="utf-8")
    command = [str(home / "bin/mvn"), "-B", "-ntp", "-s", str(settings), "-gs", str(settings),
               "-Dmaven.repo.local=" + str(repository.resolve()), "-f", str(pom),
               "org.apache.maven.plugins:maven-dependency-plugin:" + lock["dependency_plugin_version"] + ":copy-dependencies",
               "-DincludeScope=runtime", "-DoutputDirectory=" + str(target / "dependencies")]
    with (target / "prepare.log").open("w", encoding="utf-8") as output:
        try:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, text=True, timeout=30)
        except subprocess.TimeoutExpired as error:
            raise ProbeFailure("SG_PASSWORD_TOOLS", "原生密码依赖准备超时，保留日志") from error
    require(result.returncode == 0, "SG_PASSWORD_TOOLS", "原生密码依赖准备失败，保留日志")
    manifest = {"schema": 1, "lock_sha256": digest(HERE / "password.lock.json"),
                "artifacts": {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}}
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    args.password_tools = str(target)
    return environment(args)


def environment(args):
    target = pathlib.Path(args.password_tools or "")
    require(args.password_tools and target.is_dir() and not target.is_symlink(), "SG_PASSWORD_TOOLS", "缺少实际密码校准依赖")
    manifest = calibrate_http.read_json(target / "manifest.json")
    require(manifest.get("schema") == 1 and manifest.get("lock_sha256") == digest(HERE / "password.lock.json") and
            isinstance(manifest.get("artifacts"), dict) and manifest["artifacts"], "SG_PASSWORD_TOOLS", "资料不属于当前密码基线")
    require(not any(path.is_symlink() for path in target.rglob("*")), "SG_PASSWORD_TOOLS", "密码依赖不接受符号链接")
    actual = {path.relative_to(target).as_posix(): digest(path) for path in sorted(target.rglob("*.jar"))}
    lock = baseline()
    require(actual == manifest["artifacts"] and
            all("dependencies/" + name + "-" + version + ".jar" in actual for name, version in
                (("spring-security-crypto", lock["crypto_version"]), ("bcprov-jdk18on", lock["bouncycastle_version"]))),
            "SG_PASSWORD_TOOLS", "固定密码依赖缺失、增加或改变")
    _, source_tools = toolchain(args.tools)
    return {"ok": True, "proof_scope": "isolated-password-environment-only", "password_tools": str(target.resolve()),
            "artifacts": actual, "http": calibrate_http.environment(args), "source_tools": source_tools, "diagnostics": []}


def declaration(path):
    source = calibrate_http.read_json(path)
    require(set(source) == {"schema", "application", "bean"} and type(source["schema"]) is int and source["schema"] == 1 and
            isinstance(source["application"], str) and re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", source["application"]) and
            isinstance(source["bean"], str) and re.fullmatch(r"[A-Za-z_$][\w$]*", source["bean"]),
            "SG_PASSWORD_INPUT", "只接受应用类与被测 Bean，不接收生产密码、哈希或可省略场景清单")
    return source


def invoke(mode, arguments, classpath, classes, private):
    compiled = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(classes), str(HERE / "HttpInventory.java"), str(HERE / "PasswordInventory.java")])
    require(compiled.returncode == 0, "SG_PASSWORD_TOOL_FAILURE", "密码观察器实际编译失败，不能作为目标反例")
    result = execute(["java", "-cp", os.pathsep.join([str(classes), classpath]), "PasswordInventory", mode, *arguments])
    # 原始运行输出只在私有临时目录保留；公开报告不复制输入、哈希或异常消息。
    (private / (mode + ".log")).write_text(result.stdout + result.stderr, encoding="utf-8")
    require(result.returncode == 0, "SG_PASSWORD_RUNTIME", "实际应用、Bean 或成熟校验器调用失败")
    return result.stdout + result.stderr


def observations(document, native=False):
    samples = document.findall("sample")
    require(document.tag == ("native-verification" if native else "password-observation") and len(samples) == 4 and
            {sample.get("id") for sample in samples} == {"ascii", "unicode", "long", "nul"},
            "SG_PASSWORD_COVERAGE", "实际观察必须包含全部固定场景")
    results = []
    for sample in samples:
        hashes = sample.findall("verified" if native else "encoded")
        require(len(hashes) == 2, "SG_PASSWORD_COVERAGE", "每个输入必须实际生成两次哈希")
        require(all(item.get("same") == "true" and item.get("wrong") == "false" and item.get("variant") == "false" for item in hashes),
                "SG_PASSWORD_NATIVE" if native else "SG_PASSWORD_MATCH", "实际校验未正确区分原始、错误或变体密码")
        results.append({"scenario": sample.get("id"), "same": True, "wrong": False, "variant": False})
    return results


def hash_format(document):
    """只核对固定 PHC 元数据和盐；密码算法及比较全部交给成熟实现。"""
    parameters = baseline()["parameters"]
    expected = "m={memory_kib},t={iterations},p={parallelism}".format(**parameters)
    salts = set()
    for item in document.findall("sample/encoded"):
        encoded = base64.b64decode(item.text or "", validate=True).decode("utf-8")
        parts = encoded.split("$")
        require(len(parts) == 6 and parts[:3] == ["", "argon2id", "v=19"],
                "SG_PASSWORD_FORMAT", "实际保存值不是支持的 Argon2id 自描述格式")
        require(parts[3] == expected, "SG_PASSWORD_CALIBRATION", "工作参数不同于限定校准，不宣称生产参数无效或已验证")
        fields = []
        for value in parts[4:]:
            require(re.fullmatch(r"[A-Za-z0-9+/]+", value), "SG_PASSWORD_FORMAT", "PHC 二进制字段格式无效")
            decoded = base64.b64decode(value + "=" * (-len(value) % 4), validate=True)
            require(base64.b64encode(decoded).decode("ascii").rstrip("=") == value, "SG_PASSWORD_FORMAT", "PHC 编码不规范")
            fields.append(decoded)
        salt, hashed = fields
        require(len(salt) == parameters["salt_bytes"] and len(hashed) == parameters["hash_bytes"],
                "SG_PASSWORD_CALIBRATION", "盐或摘要长度超出限定校准")
        require(salt not in salts, "SG_PASSWORD_SALT", "实际重复生成复用了盐")
        salts.add(salt)


def output_check(document, output):
    """仅识别本次固定合成材料及其私有 Base64 表示，不建设通用秘密解析器。"""
    values = []
    for sample in document.findall("sample"):
        for attribute in ("raw", "variant"):
            values.append(base64.b64decode(sample.attrib[attribute], validate=True))
        values.extend(base64.b64decode(item.text or "", validate=True) for item in sample.findall("encoded"))
    for value in values:
        encoded = base64.b64encode(value).decode("ascii")
        candidates = (value.decode("utf-8"), encoded, encoded.rstrip("="))
        require(all(candidate not in output for candidate in candidates), "SG_PASSWORD_OUTPUT", "实际运行输出包含合成密码或摘要")


def probe(args):
    before, env = configuration(), environment(args)
    path = pathlib.Path(args.password)
    source, source_sha = declaration(path), digest(path)
    build = calibrate_maven.probe(args)
    require(build["ok"], "SG_PASSWORD_BUILD", "实际编译未通过")
    compilation = next(item for item in build["compilations"] if item["purpose"] == "production")
    output = pathlib.Path(compilation["output"])
    require((output / (source["application"].replace(".", "/") + ".class")).is_file(),
            "SG_PASSWORD_ENTRY_SCOPE", "应用入口不属于同次生产类产物")
    versions = {(item["group"], item["artifact"]): item["version"] for item in compilation["dependencies"]}
    lock = baseline()
    require(versions.get(("org.springframework.security", "spring-security-crypto")) == lock["crypto_version"] and
            versions.get(("org.bouncycastle", "bcprov-jdk18on")) == lock["bouncycastle_version"],
            "SG_PASSWORD_CALIBRATION", "实际密码依赖组合未校准")
    require(versions.get(("org.springframework.boot", "spring-boot")) == calibrate_http.baseline()["boot_version"],
            "SG_PASSWORD_CALIBRATION", "实际启动框架不属于固定组合")
    with tempfile.TemporaryDirectory(prefix="domain-forge-password-private-") as directory:
        private = pathlib.Path(directory)
        classes = private / "classes"
        classes.mkdir()
        observed = private / "observed.xml"
        runtime_output = invoke("observe", [source["application"], source["bean"], str(observed)],
                                os.pathsep.join(compilation["classpath"]), classes, private)
        require(observed.is_file() and observed.stat().st_size <= 65536, "SG_PASSWORD_COVERAGE", "实际私有观察缺失或超出固定范围")
        document = ET.parse(observed).getroot()
        results = observations(document)
        output_check(document, runtime_output)
        hash_format(document)
        native_classes = private / "native-classes"
        native_classes.mkdir()
        native_result = private / "native.xml"
        jars = [*pathlib.Path(args.password_tools).glob("dependencies/*.jar"),
                *pathlib.Path(args.http_tools).glob("framework/dependencies/*.jar")]
        native_output = invoke("verify", [str(observed), str(native_result)],
                               os.pathsep.join(map(str, sorted(jars))), native_classes, private)
        output_check(document, native_output)
        observations(ET.parse(native_result).getroot(), native=True)
    calibrate_maven_source.unchanged_build(build, pathlib.Path(args.project).resolve())
    require(before == configuration() and source_sha == digest(path) and env == environment(args),
            "SG_INPUT_CHANGED", "密码校准输入、类产物或工具发生改变")
    return {"ok": True, "proof_scope": "isolated-argon2id-bean-only", "build": build, "configuration": before,
            "password_definition": {"path": str(path.resolve()), "sha256": source_sha}, "environment": env,
            "bean_type": document.get("bean-type"), "interactions": results, "diagnostics": [],
            "native_hashes_verified": True, "fresh_salts_verified": True,
            "product_verified": False, "load_parameters_verified": False, "login_verified": False,
            "semantic_review_proven": False, "human_acceptance": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--prepare")
    operation.add_argument("--environment", action="store_true")
    parser.add_argument("--password")
    parser.add_argument("--password-tools", default=os.environ.get("DOMAIN_FORGE_GATE_PASSWORD_TOOLS"))
    parser.add_argument("--tools", default=os.environ.get("DOMAIN_FORGE_GATE_TOOLS"))
    parser.add_argument("--http-tools", default=os.environ.get("DOMAIN_FORGE_GATE_HTTP_TOOLS"))
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    args = parser.parse_args()
    if args.project and not args.password:
        parser.error("隔离工程必须提供应用与密码 Bean 声明")
    try:
        result = prepare(args) if args.prepare else {"ok": True, "environment": environment(args), "diagnostics": []} if args.environment else probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_PASSWORD_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
