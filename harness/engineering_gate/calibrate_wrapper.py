"""校准官方 Wrapper 启动与固定分发；不创建产品工程或证明产品构建。"""

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import urllib.request

import calibrate_maven
from calibrate_source import HERE, ProbeFailure, digest, require


def wrapper_artifact(raw):
    path = pathlib.Path(raw or "")
    require(raw and path.is_file() and not path.is_symlink() and
            digest(path) == calibrate_maven.baseline()["wrapper"]["sha256"],
            "SG_WRAPPER_ARTIFACT", "缺少固定官方 Wrapper 发行或字节不符")
    return path.resolve()


def prepare(raw):
    """只下载官方固定发行到仓库外专用空目录，不覆盖已有用户文件。"""
    target = pathlib.Path(raw)
    require(target.is_dir() and not target.is_symlink() and not any(target.iterdir()) and
            not target.resolve().is_relative_to(HERE.parents[1]),
            "SG_WRAPPER_ARTIFACT", "Wrapper 准备须使用仓库外专用空目录")
    path = target / "wrapper.zip"
    with urllib.request.urlopen(calibrate_maven.baseline()["wrapper"]["url"], timeout=30) as response, path.open("xb") as output:
        output.write(response.read(1024 * 1024 + 1))
    require(path.stat().st_size <= 1024 * 1024, "SG_WRAPPER_ARTIFACT", "官方发行超过准备上限，保留诊断文件")
    return {"ok": True, "wrapper_archive": str(wrapper_artifact(str(path))),
            "proof_scope": "fixed-wrapper-preparation-only", "diagnostics": []}


def environment(args):
    """保留实际 Maven/JDK 预检与 Wrapper 发行，缺少工具不降级。"""
    archive = wrapper_artifact(args.wrapper)
    return {"maven": calibrate_maven.environment(args)["environment"],
            "wrapper_archive": str(archive), "wrapper_sha256": digest(archive)}


def validate_project(raw):
    """仅校准真实官方脚本及显式属性；不执行调用方替换的脚本或隐式配置。"""
    project = pathlib.Path(raw)
    require(project.is_dir() and not project.is_symlink() and
            not project.resolve().is_relative_to(HERE.parents[1]), "SG_BUILD_INPUT", "只接受仓库外隔离工程")
    project = project.resolve()
    require(not (project / "target").exists() and not (project / "target").is_symlink(),
            "SG_DIRTY_BUILD", "需要无构建输出的隔离工程，不删除已有数据")
    inputs = validate_wrapper(project)
    return project, inputs


def validate_wrapper(project):
    """前后核对相同官方脚本及属性；调用方另行决定是否允许已有输出。"""
    inputs = calibrate_maven.project_inputs(project)
    baseline = calibrate_maven.baseline()
    require((project / "pom.xml").is_file(), "SG_WRAPPER_INPUT", "隔离对象缺少 POM")
    for name, checksum in baseline["wrapper"]["scripts"].items():
        path = project / name
        require(path.is_file() and digest(path) == checksum and
                (name != "mvnw" or os.access(path, os.X_OK)), "SG_WRAPPER_INPUT", "官方 Wrapper 脚本缺失、被改写或不可执行：" + name)
    properties = project / ".mvn/wrapper/maven-wrapper.properties"
    require(properties.is_file() and {path for path in inputs if pathlib.Path(path).is_relative_to(project / ".mvn")} == {str(properties)},
            "SG_WRAPPER_INPUT", "Wrapper 属性缺失或包含尚未校准的隐式 Maven 配置")
    values = {}
    for line in properties.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        require(separator and key not in values, "SG_WRAPPER_INPUT", "Wrapper 属性缺失分隔或重复")
        values[key] = value
    require(values == {"wrapperVersion": baseline["wrapper"]["version"], "distributionType": "only-script",
                       "distributionUrl": baseline["url"], "distributionSha256Sum": baseline["distribution_sha256"]},
            "SG_WRAPPER_INPUT", "Wrapper 分发、摘要、版本或属性范围与校准基线不符")
    return inputs


def configuration():
    return {name: digest(HERE / name) for name in ("calibrate_wrapper.py", "calibrate_maven.py", "calibrate_source.py", "maven-scope.lock.json")}


def bootstrap(project, cache):
    """在调用方新建缓存启动固定分发；生命周期可在同一缓存继续执行 verify。"""
    require(all(not os.environ.get(name) for name in ("MVNW_REPOURL", "MVNW_USERNAME", "MVNW_PASSWORD", "MVNW_VERBOSE")),
            "SG_WRAPPER_INPUT", "校准不接受隐式 Wrapper 地址、认证或调试覆盖")
    argv = [str(project / "mvnw"), "--version"]
    execution_environment = dict(os.environ, MAVEN_USER_HOME=str(cache), MAVEN_SKIP_RC="true")
    try:
        result = subprocess.run(argv, cwd=project, env=execution_environment, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ProbeFailure("SG_WRAPPER_FAILURE", "官方 Wrapper 执行未完成：" + type(error).__name__) from error
    if result.returncode != 0:
        failure = ProbeFailure("SG_WRAPPER_FAILURE", "官方 Wrapper 实际执行失败，不作为构建通过")
        failure.diagnostic.update(exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr)
        raise failure
    home = re.search(r"^Maven home: (.+)$", result.stdout, re.M)
    baseline = calibrate_maven.baseline()
    require(result.stdout.startswith("Apache Maven " + baseline["version"] + " ") and home and
            re.search(r"^Java version: 21[.]", result.stdout, re.M), "SG_WRAPPER_RUNTIME", "Wrapper 实际启动版本不符")
    core = pathlib.Path(home[1]) / "lib" / ("maven-core-" + baseline["version"] + ".jar")
    require(core.resolve().is_relative_to(pathlib.Path(cache).resolve()) and core.is_file() and not core.is_symlink() and
            digest(core) == baseline["core_sha256"], "SG_WRAPPER_RUNTIME", "Wrapper 未使用新隔离下载的固定核心")
    return {"command": argv, "exit_code": result.returncode, "stdout": result.stdout, "stderr": result.stderr,
            "home": str(pathlib.Path(home[1]).resolve()), "core_sha256": digest(core)}


def probe(args):
    before_environment = environment(args)
    before_configuration = configuration()
    project, inputs = validate_project(args.project)
    with tempfile.TemporaryDirectory(prefix="domain-forge-wrapper-bootstrap-") as cache:
        boot = bootstrap(project, cache)
    require(inputs == validate_project(args.project)[1] and before_configuration == configuration() and
            before_environment == environment(args), "SG_INPUT_CHANGED", "执行期间工程、配置或必要环境变化")
    return {"ok": True, "proof_scope": "isolated-wrapper-bootstrap-only", "distribution_bootstrapped": True,
            "build_proven": False, "publication_proven": False, "human_acceptance": False,
            "command": boot["command"], "exit_code": boot["exit_code"], "stdout": boot["stdout"], "stderr": boot["stderr"],
            "distribution_core_sha256": boot["core_sha256"], "inputs": inputs, "configuration": before_configuration,
            "environment": before_environment, "isolated_maven_cache": True, "maven_rc_loaded": False, "diagnostics": []}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--environment", action="store_true")
    operation.add_argument("--prepare", metavar="EMPTY_DIRECTORY")
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    parser.add_argument("--wrapper", default=os.environ.get("DOMAIN_FORGE_GATE_WRAPPER"))
    args = parser.parse_args()
    try:
        if args.prepare:
            report = prepare(args.prepare)
        elif args.environment:
            report = {"ok": True, "proof_scope": "wrapper-environment-only", "environment": environment(args), "diagnostics": []}
        else:
            report = probe(args)
    except ProbeFailure as error:
        report = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError) as error:
        report = {"ok": False, "diagnostics": [{"code": "SG_WRAPPER_INPUT", "message": type(error).__name__}]}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
