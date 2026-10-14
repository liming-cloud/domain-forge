"""第五层原生运行依赖，资料准备与场景断言分离。"""

import json
import os
import pathlib
import re
import subprocess

import calibrate_flyway
import calibrate_http
import calibrate_maven
from calibrate_source import HERE, ProbeFailure, digest, require, toolchain


def baseline():
    lock = calibrate_http.read_json(HERE / "runtime.lock.json")
    require(lock.get("schema") == 1 and re.fullmatch(r"nacos/nacos-server@sha256:[a-f0-9]{64}", lock["nacos_image"]),
            "SG_RUNTIME_TOOLS", "运行校准须固定依赖和真实服务镜像")
    return lock


def framework_pom():
    return calibrate_http.framework_pom().removesuffix("</dependencies>") + \
        calibrate_http.dependency("com.alibaba.cloud", "spring-cloud-starter-alibaba-nacos-config", baseline()["cloud_alibaba_version"]) + \
        calibrate_http.dependency("io.opentelemetry", "opentelemetry-sdk") + \
        calibrate_http.dependency("io.opentelemetry", "opentelemetry-exporter-otlp") + "</dependencies>"


def prepare(args):
    target = pathlib.Path(args.prepare)
    require(target.is_dir() and not target.is_symlink() and not any(target.iterdir()) and
            not target.resolve().is_relative_to(HERE.parents[1]), "SG_RUNTIME_TOOLS", "准备须使用仓库外专用空目录")
    home, _ = calibrate_maven.runtime(args.maven_home)
    repository = pathlib.Path(args.repository or "")
    require(args.repository and repository.is_dir() and not repository.is_symlink(), "SG_RUNTIME_TOOLS", "须使用专用 Maven 缓存")
    settings, pom = target / "settings.xml", target / "pom.xml"
    settings.write_text('<settings><mirrors><mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf>'
                        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>')
    pom.write_text('<project><modelVersion>4.0.0</modelVersion><groupId>probe</groupId>'
                   '<artifactId>runtime</artifactId><version>1.0.0</version>' + framework_pom() + '</project>')
    command = [str(home / "bin/mvn"), "-B", "-ntp", "-s", str(settings), "-gs", str(settings),
               "-Dmaven.repo.local=" + str(repository.resolve()), "-f", str(pom),
               "org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy-dependencies",
               "-DincludeScope=runtime", "-DoutputDirectory=" + str(target / "dependencies")]
    with (target / "prepare.log").open("w") as output:
        try:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=600)
        except subprocess.TimeoutExpired as error:
            raise ProbeFailure("SG_RUNTIME_PREPARATION", "依赖准备超时，保留日志，不据此判断 CI 编排失效") from error
    require(result.returncode == 0, "SG_RUNTIME_PREPARATION", "依赖准备未完成，未运行场景不记通过")
    manifest = {"schema": 1, "lock_sha256": digest(HERE / "runtime.lock.json"),
                "artifacts": {p.relative_to(target).as_posix(): digest(p) for p in sorted(target.rglob("*.jar"))}}
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2))
    endpoint = calibrate_flyway.local_endpoint()
    image = baseline()["nacos_image"]
    existing = subprocess.run(["docker", "--host", endpoint, "image", "inspect", image], capture_output=True, timeout=30)
    if existing.returncode:
        pull = subprocess.run(["docker", "--host", endpoint, "pull", image], capture_output=True, timeout=600)
        require(pull.returncode == 0, "SG_RUNTIME_PREPARATION", "真实服务镜像准备未完成")
    args.runtime_tools = str(target)
    return environment(args)


def environment(args):
    _, java = toolchain(os.environ.get("DOMAIN_FORGE_GATE_TOOLS"))
    target = pathlib.Path(args.runtime_tools or "")
    require(args.runtime_tools and target.is_dir() and not target.is_symlink(), "SG_RUNTIME_TOOLS", "缺少运行校准依赖")
    manifest = calibrate_http.read_json(target / "manifest.json")
    require(not any(p.is_symlink() for p in target.rglob("*")), "SG_RUNTIME_TOOLS", "工具资料不得使用符号链接")
    artifacts = {p.relative_to(target).as_posix(): digest(p) for p in sorted(target.rglob("*.jar"))}
    lock = baseline()
    expected = {"spring-boot": calibrate_http.baseline()["boot_version"],
                "spring-cloud-starter-alibaba-nacos-config": lock["cloud_alibaba_version"],
                "nacos-client": lock["nacos_client_version"], "opentelemetry-sdk": lock["opentelemetry_version"]}
    require(manifest.get("schema") == 1 and manifest.get("lock_sha256") == digest(HERE / "runtime.lock.json") and
            artifacts == manifest.get("artifacts") and all("dependencies/" + name + "-" + version + ".jar" in artifacts
            for name, version in expected.items()), "SG_RUNTIME_TOOLS", "运行资料缺失、字节或版本漂移")
    endpoint = calibrate_flyway.local_endpoint()
    images = json.loads(calibrate_flyway.docker("image", "inspect", lock["nacos_image"], "--format", "{{json .RepoDigests}}", endpoint=endpoint))
    require(lock["nacos_image"] in images, "SG_RUNTIME_TOOLS", "缺少固定真实 Nacos 镜像")
    return {"ok": True, "proof_scope": "isolated-runtime-environment-only", "runtime_tools": str(target.resolve()),
            "artifacts": artifacts, "java": java, "diagnostics": []}
