"""真实工程的固定环境、输入快照和原生命令；不读取历史成功报告。"""

import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time

ENGINEERING = Path(__file__).resolve().parents[1] / "engineering_gate"
sys.path.insert(0, str(ENGINEERING))
from calibrate_source import ProbeFailure, digest, require

PRODUCT = Path("platform")
MODULE = Path("verification/smoke/boot-smoke")
JAVA_MODULES = (MODULE, Path("framework/domain-forge-web-support"), Path("verification/consumers/consumer-a"), Path("verification/consumers/consumer-parent/consumer-b"))
AGGREGATES = {
    Path("."): ("framework", "domain-forge-bom", "verification"),
    Path("framework"): ("domain-forge-web-support",),
    Path("verification"): ("smoke",),
    Path("verification/smoke"): ("boot-smoke",),
}
REACTOR = (Path("framework"), Path("framework/domain-forge-web-support"), Path("domain-forge-bom"),
           Path("verification"), Path("verification/smoke"), MODULE)
CONSUMER_AGGREGATE = Path("verification/consumers")
CONSUMERS = (Path("verification/consumers/consumer-a"), Path("verification/consumers/consumer-parent"))
CONTRACTS = Path("verification/contracts/web-support/0.1.0-r2.1")
CONTRACT_FILES = tuple(CONTRACTS / name for name in ("manifest.json", "consumer-a-openapi.json", "consumer-b-openapi.json"))


def product_root(project):
    """探针相对位置固定，拒绝把错误目录当成聚合或 Wrapper 根。"""
    code = project.parents[len(MODULE.parts) - 1]
    require(code.name == PRODUCT.name and project == code / MODULE,
            "PG_INPUT", "探针不属于固定产品布局")
    return code


def contract_sources(root):
    """只登记三个派生合同资源，不把 manifest 纳入自身源输入。"""
    paths = [root / PRODUCT / name for name in CONTRACT_FILES]
    require(all(path.is_file() and not path.is_symlink() for path in paths),
            "PG_INPUT", "缺少普通合同资源")
    return {path.relative_to(root).as_posix(): digest(path) for path in paths}


def baseline(root):
    return json.loads((root / "harness/product_gate/baseline.json").read_text())


def command(argv, cwd, log, timeout=600, env=None, preparation=False):
    """准备错误与实际断言失败分别标记；超时不产生成功或空报告。"""
    log.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    with log.open("w") as stream:
        try:
            result = subprocess.run(list(map(str, argv)), cwd=cwd, env=env,
                                    stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            failure = ProbeFailure("PG_PREPARATION" if preparation else "PG_EXECUTION_TIMEOUT",
                                   "原生准备未完成" if preparation else "原生执行超时，未取得完整检查结果")
            failure.diagnostic.update(log=str(log), seconds=timeout)
            raise failure from error
    execution = {"command": list(map(str, argv)), "exit_code": result.returncode,
                 "log": str(log), "seconds": round(time.monotonic() - started, 3)}
    if result.returncode:
        text = log.read_text(errors="replace")
        network = any(value in text for value in ("Could not transfer artifact", "Could not resolve dependencies",
                      "UnknownHostException", "Connection timed out", "Could not resolve plugin"))
        failure = ProbeFailure("PG_PREPARATION" if preparation or network else "PG_NATIVE_FAILURE",
                               "依赖或环境准备未完成" if preparation or network else "原生执行失败")
        failure.diagnostic.update(execution)
        raise failure
    return execution


def runtime(root):
    lock = baseline(root)
    for key in ("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_PROJECTBASEDIR"):
        require(not os.environ.get(key), "PG_ENVIRONMENT", "交付入口不接受隐式参数：" + key)
    require(not any(key.startswith(("SPRING_", "SERVER_", "MANAGEMENT_")) for key in os.environ),
            "PG_ENVIRONMENT", "交付入口不接受未声明的应用环境覆盖")
    result = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True, timeout=10)
    properties = dict(re.findall(r"^\s*([\w.]+) = (.*)$", result.stdout + result.stderr, re.M))
    require(result.returncode == 0 and properties.get("java.vendor") == lock["java"]["vendor"] and
            properties.get("java.runtime.version") == lock["java"]["runtime_version"],
            "PG_ENVIRONMENT", "实际 JDK 发行版或补丁不符；请按版本基线选择 JAVA_HOME 和 PATH")
    java_home = Path(properties["java.home"]).resolve()
    require(Path(os.environ.get("JAVA_HOME", "/missing")).resolve() == java_home and
            Path(shutil.which("javac") or "/missing").resolve().is_relative_to(java_home),
            "PG_ENVIRONMENT", "JAVA_HOME、java 与 javac 必须属于同一固定 JDK")
    require(platform.system() + "-" + platform.machine() in lock["java"]["platforms"],
            "PG_ENVIRONMENT", "当前操作系统／架构未列入本单元支持范围")
    require(platform.python_version() == lock["python"], "PG_ENVIRONMENT", "Python 版本与声明不符")
    return {"java": {key: properties[key] for key in ("java.vendor", "java.runtime.version", "java.home")},
            "python": platform.python_version(), "platform": platform.platform()}


def wrapper(root, code=None):
    lock = baseline(root)
    code = code or root / PRODUCT
    for name, expected in lock["wrapper"]["scripts"].items():
        require((code / name).is_file() and not (code / name).is_symlink() and digest(code / name) == expected,
                "PG_WRAPPER", "官方 Wrapper 脚本缺失或被改写：" + name)
    path = code / ".mvn/wrapper/maven-wrapper.properties"
    require(path.is_file() and not path.is_symlink(), "PG_WRAPPER", "缺少普通 Wrapper 配置")
    values = dict(line.split("=", 1) for line in path.read_text().splitlines() if line and not line.startswith("#"))
    expected = {"wrapperVersion": lock["wrapper"]["version"], "distributionType": "only-script",
                "distributionUrl": lock["maven"]["url"], "distributionSha256Sum": lock["maven"]["sha256"]}
    require(values == expected, "PG_WRAPPER", "Wrapper 分发、版本或校验与产品基线不符")
    require({p.relative_to(code / ".mvn").as_posix() for p in (code / ".mvn").rglob("*") if p.is_file()} ==
            {"wrapper/maven-wrapper.properties"}, "PG_INPUT", "存在尚未支持的隐式 Maven 配置")


def sources(root):
    """覆盖代码区全部维护文件；未支持模块不能被复制清单静默遗漏。"""
    require(not any((root / name).exists() or (root / name).is_symlink()
                    for name in ("pom.xml", "src", ".mvn", "mvnw", "mvnw.cmd", "code", "docs/contracts")),
            "PG_BUILD_SCOPE", "旧根目录工程输入必须迁入代码区")
    code = root / PRODUCT
    contract_sources(root)
    for directory in (code, code / ".mvn", *(code / module / "src" for module in JAVA_MODULES)):
        require(directory.is_dir() and not directory.is_symlink(), "PG_INPUT", "缺少普通工程输入目录")
    required = {"pom.xml", "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties", str(MODULE / "pom.xml")}
    required.update(str(module / "pom.xml") for module in (*REACTOR, *JAVA_MODULES, *CONSUMERS, CONSUMER_AGGREGATE))
    required.update(str(consumer / name) for consumer in CONSUMERS for name in ("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties"))
    paths = []
    for path in code.rglob("*"):
        require(not path.is_symlink(), "PG_INPUT", "工程输入不可为符号链接")
        if any(path.is_relative_to(code / module / "target") for module in (Path("."), *REACTOR, *JAVA_MODULES, *CONSUMERS, CONSUMER_AGGREGATE)):
            continue
        if path.is_file():
            relative = path.relative_to(code)
            if relative in CONTRACT_FILES:
                continue
            require(relative.as_posix() in required or any(relative.is_relative_to(module / "src") for module in JAVA_MODULES),
                    "PG_BUILD_SCOPE", "未登记或未支持的代码输入：" + relative.as_posix())
            paths.append(path)
    require(required <= {p.relative_to(code).as_posix() for p in paths}, "PG_INPUT", "缺少必要工程输入")
    return {path.relative_to(root).as_posix(): digest(path) for path in sorted(paths)}


def copy_project(root, target, inputs):
    target.mkdir()
    for name, checksum in {**inputs, **contract_sources(root)}.items():
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / name, destination)
        require(digest(destination) == checksum, "PG_INPUT_CHANGED", "复制期间输入变化")
    return target / PRODUCT / MODULE


def bootstrap(root, project, cache, repository, output):
    wrapper(root)
    repository.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)
    settings = output / "settings.xml"
    settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><mirrors><mirror>'
                        '<id>product-central</id><mirrorOf>*</mirrorOf>'
                        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>')
    env = dict(os.environ, MAVEN_USER_HOME=str(cache), MAVEN_SKIP_RC="true")
    argv = [str(product_root(project) / "mvnw"), "-B", "-ntp", "-s", str(settings), "-gs", str(settings),
            "-Dmaven.repo.local=" + str(repository)]
    execution = command([*argv, "--version"], project, output / "wrapper.log", 300, env, preparation=True)
    text = (output / "wrapper.log").read_text()
    match = re.search(r"^Maven home: (.+)$", text, re.M)
    require(match and "Apache Maven " + baseline(root)["maven"]["version"] + " " in text,
            "PG_WRAPPER", "实际 Wrapper Maven 版本不符")
    home = Path(match[1]).resolve()
    require(home.is_relative_to(cache), "PG_WRAPPER", "实际 Maven 不属于本次 Wrapper 缓存")
    core = home / ("lib/maven-core-" + baseline(root)["maven"]["version"] + ".jar")
    require(core.is_file() and not core.is_symlink() and digest(core) == baseline(root)["maven"]["core_sha256"],
            "PG_WRAPPER", "实际 Maven 核心偏离已校验的官方分发")
    execution["core_sha256"] = digest(core)
    return argv, env, home, execution
