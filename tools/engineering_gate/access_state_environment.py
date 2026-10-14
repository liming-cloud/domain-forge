"""访问状态校准的固定原生依赖和本轮私有容器边界。"""

import json
import pathlib
import re
import stat
import subprocess

import calibrate_access
import calibrate_flyway
import calibrate_http
import calibrate_maven
import calibrate_password
from calibrate_source import HERE, ProbeFailure, digest, execute, require


def baseline():
    lock = calibrate_flyway.read_json(HERE / "access-state.lock.json")
    require(lock.get("schema") == 1 and lock.get("redis_adapter") == "sa-token-redis-template" and
            re.fullmatch(r"redis@sha256:[0-9a-f]{64}", lock.get("redis_image", "")),
            "SG_ACCESS_STATE_CALIBRATION", "访问状态须绑定固定原生 Redis 组合")
    return lock


def framework_pom():
    access, password = calibrate_access.baseline(), calibrate_password.baseline()
    dependencies = [("cn.dev33", access["starter"], access["sa_token_version"]),
                    ("cn.dev33", baseline()["redis_adapter"], access["sa_token_version"]),
                    ("org.springframework.boot", "spring-boot-starter-jdbc", None),
                    ("org.postgresql", "postgresql", calibrate_flyway.baseline()["driver_version"]),
                    ("org.springframework.security", "spring-security-crypto", password["crypto_version"]),
                    ("org.bouncycastle", "bcprov-jdk18on", password["bouncycastle_version"])]
    return calibrate_http.framework_pom().removesuffix("</dependencies>") + "".join(
        calibrate_http.dependency(*item) for item in dependencies) + "</dependencies>"


def prepare(args):
    target = pathlib.Path(args.prepare)
    require(target.is_dir() and not target.is_symlink() and not any(target.iterdir()) and
            not target.resolve().is_relative_to(HERE.parents[1]), "SG_ACCESS_STATE_TOOLS", "准备须使用仓库外专用空目录")
    home, _ = calibrate_maven.runtime(args.maven_home)
    repository = pathlib.Path(args.repository or "")
    require(args.repository and repository.is_dir() and not repository.is_symlink(), "SG_ACCESS_STATE_TOOLS", "须使用专用 Maven 缓存")
    settings, pom = target / "settings.xml", target / "pom.xml"
    settings.write_text('<settings><mirrors><mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf>'
                        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>', encoding="utf-8")
    pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                   '<groupId>probe</groupId><artifactId>access-state-tools</artifactId><version>1.0.0</version>' +
                   framework_pom() + '</project>', encoding="utf-8")
    command = [str(home / "bin/mvn"), "-B", "-ntp", "-s", str(settings), "-gs", str(settings),
               "-Dmaven.repo.local=" + str(repository.resolve()), "-f", str(pom),
               "org.apache.maven.plugins:maven-dependency-plugin:" + calibrate_access.baseline()["dependency_plugin_version"] + ":copy-dependencies",
               "-DincludeScope=runtime", "-DoutputDirectory=" + str(target / "dependencies")]
    with (target / "prepare.log").open("w", encoding="utf-8") as output:
        try:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=30)
        except subprocess.TimeoutExpired as error:
            raise ProbeFailure("SG_ACCESS_STATE_TOOLS", "依赖准备超时，保留日志；不据此认定 CI 失效") from error
    require(result.returncode == 0, "SG_ACCESS_STATE_TOOLS", "依赖准备未完成，保留原生日志")
    manifest = {"schema": 1, "lock_sha256": digest(HERE / "access-state.lock.json"),
                "artifacts": {p.relative_to(target).as_posix(): digest(p) for p in sorted(target.rglob("*.jar"))}}
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    endpoint = calibrate_flyway.local_endpoint()
    for image in (baseline()["redis_image"], calibrate_flyway.baseline()["image"]):
        if execute(["docker", "--host", endpoint, "image", "inspect", image]).returncode != 0:
            calibrate_flyway.docker("pull", image, endpoint=endpoint)
    args.access_state_tools = str(target)
    return environment(args)


def required_versions():
    access, http, password, lock = calibrate_access.baseline(), calibrate_http.baseline(), calibrate_password.baseline(), baseline()
    return {("cn.dev33", name): access["sa_token_version"] for name in
            (access["starter"], "sa-token-core", lock["redis_adapter"])} | {
        ("org.springframework.boot", "spring-boot"): http["boot_version"],
        ("org.springframework", "spring-webmvc"): http["spring_version"],
        ("org.springframework", "spring-jdbc"): http["spring_version"],
        ("org.springframework.data", "spring-data-redis"): lock["spring_data_redis_version"],
        ("io.lettuce", "lettuce-core"): lock["lettuce_version"],
        ("org.postgresql", "postgresql"): calibrate_flyway.baseline()["driver_version"],
        ("org.springframework.security", "spring-security-crypto"): password["crypto_version"],
        ("org.bouncycastle", "bcprov-jdk18on"): password["bouncycastle_version"]}


def environment(args):
    target = pathlib.Path(args.access_state_tools or "")
    require(args.access_state_tools and target.is_dir() and not target.is_symlink(), "SG_ACCESS_STATE_TOOLS", "缺少真实访问状态依赖")
    manifest = calibrate_flyway.read_json(target / "manifest.json")
    require(not any(p.is_symlink() for p in target.rglob("*")), "SG_ACCESS_STATE_TOOLS", "工具资料不得为符号链接")
    artifacts = {p.relative_to(target).as_posix(): digest(p) for p in sorted(target.rglob("*.jar"))}
    require(manifest.get("schema") == 1 and manifest.get("lock_sha256") == digest(HERE / "access-state.lock.json") and
            artifacts == manifest.get("artifacts") and all("dependencies/" + name + "-" + version + ".jar" in artifacts
            for (_, name), version in required_versions().items()), "SG_ACCESS_STATE_TOOLS", "原生依赖资料缺失、版本或字节改变")
    endpoint = calibrate_flyway.local_endpoint()
    for image in (baseline()["redis_image"], calibrate_flyway.baseline()["image"]):
        digests = json.loads(calibrate_flyway.docker("image", "inspect", image, "--format", "{{json .RepoDigests}}", endpoint=endpoint))
        require(image in digests, "SG_ACCESS_STATE_TOOLS", "缺少固定数据库镜像，不跳过真实接入")
    return {"ok": True, "proof_scope": "isolated-access-state-environment-only", "access_state_tools": str(target.resolve()),
            "artifacts": artifacts, "diagnostics": []}


def connections(path):
    require(path.is_file() and not path.is_symlink() and stat.S_IMODE(path.stat().st_mode) == 0o600,
            "SG_ACCESS_STATE_CONNECTION", "连接须为 0600 普通私有文件")
    data = calibrate_flyway.read_json(path)
    require(set(data) == {"schema", "postgres", "redis"} and type(data["schema"]) is int and data["schema"] == 1,
            "SG_ACCESS_STATE_CONNECTION", "须明确本轮 PostgreSQL 与 Redis")
    endpoint = calibrate_flyway.local_endpoint()
    calibrate_flyway.owned_connection(data["postgres"], endpoint)
    source = data["redis"]
    require(isinstance(source, dict) and set(source) == {"container", "port", "password"} and
            isinstance(source["container"], str) and re.fullmatch(r"[0-9a-f]{64}", source["container"]) and
            type(source["port"]) is int and 0 < source["port"] < 65536 and
            isinstance(source["password"], str) and re.fullmatch(r"[0-9a-f]{48}", source["password"]),
            "SG_ACCESS_STATE_CONNECTION", "只接受固定本地合成 Redis 连接")
    state = json.loads(calibrate_flyway.docker("inspect", source["container"], endpoint=endpoint))[0]
    ports = state["NetworkSettings"]["Ports"].get("6379/tcp") or []
    require(state["Id"] == source["container"] and state["State"]["Running"] and not state["State"]["Paused"] and
            state["Config"].get("Labels", {}).get("org.domainforge.calibration") == "access-state" and
            state["Config"]["Image"] == baseline()["redis_image"] and state["HostConfig"]["AutoRemove"] and
            not state["HostConfig"].get("Binds") and not state["HostConfig"].get("Mounts") and
            set(state["HostConfig"].get("Tmpfs", {})) == {"/data"} and
            [(m.get("Type"), m.get("Destination")) for m in state.get("Mounts", [])] in ([], [("tmpfs", "/data")]) and
            ports == [{"HostIp": "127.0.0.1", "HostPort": str(source["port"])}],
            "SG_ACCESS_STATE_CONNECTION", "Redis 必须属于无宿主数据挂载的本轮临时容器")
    return data, endpoint
