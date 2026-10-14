"""校准真实 Wrapper verify 生命周期；产品构建与发布仍须在第六层验证。"""

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

import calibrate_maven
import calibrate_wrapper
from calibrate_source import HERE, ProbeFailure, digest, require


def baseline():
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "SG_BUILD_BASELINE", "生命周期基线含重复字段")
            result[key] = value
        return result
    return json.loads((HERE / "maven-lifecycle.lock.json").read_text(encoding="utf-8"), object_pairs_hook=unique)


def configuration():
    names = ("calibrate_lifecycle.py", "calibrate_maven.py", "calibrate_wrapper.py", "calibrate_source.py",
             "MavenScopeSpy.java", "maven-scope.lock.json", "maven-lifecycle.lock.json")
    return {name: digest(HERE / name) for name in names}


def environment(args):
    """统一入口核对已经准备的生命周期插件；缺少环境不跳过或下载后伪称原环境。"""
    observed = calibrate_wrapper.environment(args)
    observed["lifecycle_plugins"] = {}
    for name in ("surefire", "jar"):
        version = baseline()[name + "_version"]
        artifact = pathlib.Path(args.repository) / "org/apache/maven/plugins" / ("maven-" + name + "-plugin") / version / ("maven-" + name + "-plugin-" + version + ".jar")
        require(artifact.is_file() and not artifact.is_symlink(), "SG_MAVEN_RUNTIME", "生命周期插件尚未准备：" + name)
        observed["lifecycle_plugins"][name] = {"version": version, "sha256": digest(artifact)}
    return observed


def contract_input(raw):
    """必要测试来自场景合同，不从当前 Surefire 发现结果反推一个永远通过的清单。"""
    path = pathlib.Path(raw or "")
    require(raw and path.is_file() and not path.is_symlink(), "SG_BUILD_CONTRACT", "缺少显式必需测试合同")
    try:
        def unique(pairs):
            result = {}
            for key, value in pairs:
                require(key not in result, "SG_BUILD_CONTRACT", "测试合同含重复字段")
                result[key] = value
            return result
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (ValueError, UnicodeError) as error:
        raise ProbeFailure("SG_BUILD_CONTRACT", "测试合同不是有效 JSON") from error
    require(isinstance(value, dict) and set(value) == {"schema", "required_tests"} and
            type(value["schema"]) is int and value["schema"] == 1, "SG_BUILD_CONTRACT", "测试合同字段或版本不符")
    tests = value["required_tests"]
    require(isinstance(tests, list) and tests and all(isinstance(item, str) and
            re.fullmatch(r"[^#\s]+#[^#\r\n]+", item) for item in tests) and len(tests) == len(set(tests)),
            "SG_BUILD_CONTRACT", "必需测试清单为空、重复或身份不完整")
    return path.resolve(), digest(path), set(tests)


def execute_verify(project, home, repository, temporary, cache):
    """固定调用官方 Wrapper verify；不接受调用方替换目标或附加跳过参数。"""
    extension = calibrate_maven.observer(home, temporary)
    output = temporary / "lifecycle.xml"
    settings = temporary / "settings.xml"
    settings.write_text('''<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><mirrors>
<mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror>
</mirrors></settings>''', encoding="utf-8")
    argv = [str(project / "mvnw"), "--batch-mode", "--no-transfer-progress", "--settings", str(settings),
            "-Dmaven.repo.local=" + str(repository), "-Dmaven.ext.class.path=" + str(extension),
            "-Ddomainforge.scope.output=" + str(output), "verify"]
    try:
        result = subprocess.run(argv, cwd=project, env=dict(os.environ, MAVEN_USER_HOME=str(cache), MAVEN_SKIP_RC="true"),
                                capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ProbeFailure("SG_MAVEN_FAILURE", "真实 verify 未完成：" + type(error).__name__) from error
    log = project / "target/maven-lifecycle.log"
    log.parent.mkdir(exist_ok=True)
    log.write_text(result.stdout + result.stderr, encoding="utf-8")
    execution = {"command": argv, "exit_code": result.returncode, "log": str(log)}
    if result.returncode != 0:
        failure = ProbeFailure("SG_MAVEN_FAILURE", "真实 verify 执行失败，保留原生日志")
        failure.diagnostic.update(execution)
        raise failure
    require(output.is_file(), "SG_BUILD_OBSERVER", "实际 verify 未生成观察结果")
    inventory = project / "target/maven-lifecycle.xml"
    inventory.write_bytes(output.read_bytes())
    execution["inventory"] = {"path": str(inventory), "sha256": digest(inventory)}
    return ET.parse(output).getroot(), execution


def lifecycle_goals(document, project):
    """成功事件仍须覆盖必需生命周期；目标名称存在于 POM 并不足够。"""
    require([item.attrib["goal"] for item in document.findall("requested")] == ["verify"],
            "SG_BUILD_LIFECYCLE", "实际请求不是固定 verify")
    goals = [dict(item.attrib) for item in document.findall("execution")]
    require(goals and all(pathlib.Path(item["project"]).resolve() == project for item in goals),
            "SG_BUILD_SCOPE", "目标执行缺失或越出当前单工程范围")
    versions = dict(calibrate_maven.baseline(), **baseline())
    required = {("resources", "resources", "process-resources"), ("resources", "testResources", "process-test-resources"),
                ("compiler", "compile", "compile"), ("compiler", "testCompile", "test-compile"),
                ("surefire", "test", "test"), ("jar", "jar", "package")}
    for name, goal, phase in sorted(required):
        found = [item for item in goals if item["group"] == "org.apache.maven.plugins" and
                 item["artifact"] == "maven-" + name + "-plugin" and item["goal"] == goal and item["phase"] == phase]
        require(found, "SG_BUILD_LIFECYCLE", "未执行必需目标或绑定阶段偏离：" + name + ":" + goal)
        if any(item["version"] != versions[name + "_version"] for item in found):
            failure = ProbeFailure("SG_BUILD_BASELINE", "实际必需插件版本不符：" + name)
            failure.diagnostic.update(observed=found, expected=versions[name + "_version"])
            raise failure
    return goals


def fixed_version(value):
    return bool(isinstance(value, str) and re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]*", value) and
                value.upper() not in {"LATEST", "RELEASE", "NULL"} and "SNAPSHOT" not in value.upper())


def compilation_target(compilations):
    """从实际类文件核对编译目标；JDK 运行版本正确不证明 release 没有偏离。"""
    release = baseline()["java_release"]
    header = b"\xca\xfe\xba\xbe\x00\x00" + (release + 44).to_bytes(2, "big")
    invalid = [name for item in compilations for name in item["classes"] if pathlib.Path(name).read_bytes()[:8] != header]
    if invalid:
        failure = ProbeFailure("SG_BUILD_BASELINE", "实际字节码编译目标偏离固定 Java release 或使用未校准预览形式")
        failure.diagnostic.update(expected_release=release, invalid=invalid)
        raise failure


def versions_and_artifacts(document, repository):
    """同时核对有效声明和最终解析对象；固定解析值不能掩盖原范围或基础快照版本。"""
    model = document.find("project")
    declared = [dict(model.attrib), *[dict(item.attrib) for tag in ("declaredDependency", "declaredPlugin", "declaredPluginDependency")
                                      for item in model.findall(tag)]]
    dependencies = [dict(item.attrib) for item in model.findall("resolvedDependency")]
    plugin_dependencies = [dict(item.attrib) for item in document.findall("execution/pluginDependency")]
    resolutions = document.findall("dependencyResolution")
    require(resolutions and all(all(item.attrib[field] == model.attrib[field] for field in ("group", "artifact", "version"))
                                for item in resolutions), "SG_BUILD_RESOLUTION", "缺少本次工程原生依赖图或图归属不符")
    constraints = [dict(node.attrib) for item in resolutions for node in item.findall("constraint")]
    plugin_constraints = [dict(node.attrib) for node in document.findall("pluginConstraint")]
    invalid = [item for item in [*declared, *dependencies, *plugin_dependencies, *constraints, *plugin_constraints] if not fixed_version(item.get("version")) or
               ("baseVersion" in item and not fixed_version(item["baseVersion"])) or
               ("constraint" in item and not fixed_version(item["constraint"]))]
    if invalid:
        failure = ProbeFailure("SG_BUILD_VERSION", "有效声明或最终解析包含未固定、浮动或快照版本")
        failure.diagnostic.update(invalid=invalid, resolved_dependencies=dependencies)
        raise failure
    require(dependencies and plugin_dependencies, "SG_BUILD_RESOLUTION", "缺少实际项目或插件解析范围")
    def identity(item):
        return tuple(item[field] for field in ("group", "artifact", "version", "extension", "classifier"))
    require({identity(item) for item in dependencies} == {identity(item) for item in constraints},
            "SG_BUILD_RESOLUTION", "实际项目解析对象与原生约束图覆盖不一致")
    require({identity(item) for item in plugin_dependencies} == {identity(item) for item in plugin_constraints},
            "SG_BUILD_RESOLUTION", "实际插件解析对象与原生约束覆盖不一致")
    artifacts = {}
    for item in [*dependencies, *plugin_dependencies]:
        path = pathlib.Path(item["file"])
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(repository),
                "SG_BUILD_RESOLUTION", "实际解析对象缺失或越出专用依赖目录")
        item["sha256"] = digest(path)
        artifacts[str(path)] = item["sha256"]
    return dependencies, plugin_dependencies, constraints, plugin_constraints, artifacts


def test_results(project, compilations, required_tests):
    """原生 XML 必须有真实用例、零跳过，并覆盖独立登记的必需场景。"""
    reports = sorted((project / "target/surefire-reports").glob("TEST-*.xml"))
    require(reports and not (project / "target/surefire-reports").is_symlink(),
            "SG_BUILD_TESTS", "没有原生 Surefire XML 或输出目录为链接，测试可能跳过、未发现或报告被关闭")
    classes = {pathlib.Path(path).relative_to(item["output"]).with_suffix("").as_posix().replace("/", ".")
               for item in compilations if item["purpose"] == "test" for path in item["classes"]}
    executed, hashes = set(), {}
    for path in reports:
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(project / "target"),
                "SG_BUILD_TESTS", "原生报告不是本次工程的普通文件")
        suite = ET.parse(path).getroot()
        cases = suite.findall("testcase")
        require(suite.tag == "testsuite" and int(suite.attrib["tests"]) == len(cases) and cases and
                all(int(suite.attrib[field]) == 0 for field in ("failures", "errors", "skipped")),
                "SG_BUILD_TESTS", "测试为空、失败、跳过或原生计数与用例不一致")
        for case in cases:
            name = case.attrib["classname"] + "#" + case.attrib["name"]
            require(case.attrib["classname"] in classes and name not in executed and
                    not any(case.find(tag) is not None for tag in ("failure", "error", "skipped")),
                    "SG_BUILD_TESTS", "用例来源不明、重复或未成功执行")
            executed.add(name)
        hashes[str(path)] = digest(path)
    missing = required_tests - executed
    if missing:
        failure = ProbeFailure("SG_BUILD_TEST_COVERAGE", "部分测试通过不能代替缺失的必需场景")
        failure.diagnostic.update(missing=sorted(missing), executed=sorted(executed))
        raise failure
    return {"count": len(executed), "executed": sorted(executed), "required": sorted(required_tests), "reports": hashes}


def packaged_artifact(document, project, compilations):
    """检查真实主产物的类字节和坐标，不以文件存在或 jar 目标成功代替内容。"""
    model = document.find("project")
    path = pathlib.Path(model.attrib["artifactFile"])
    require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(project / "target"),
            "SG_BUILD_ARTIFACT", "缺少当前工程真实主产物")
    classes = {pathlib.Path(name).relative_to(item["output"]).as_posix(): pathlib.Path(name)
               for item in compilations if item["purpose"] == "production" for name in item["classes"]}
    with zipfile.ZipFile(path) as artifact:
        names = artifact.namelist()
        require(len(names) == len(set(names)), "SG_BUILD_ARTIFACT", "主产物含重复条目")
        calibrate_maven.exact_coverage(set(classes), {name for name in names if name.endswith(".class")},
                                      "SG_BUILD_ARTIFACT", "主产物类范围与真实生产编译不一致")
        require(all(artifact.read(name) == source.read_bytes() for name, source in classes.items()),
                "SG_BUILD_ARTIFACT", "主产物类字节与实际编译不一致")
        properties = "META-INF/maven/" + model.attrib["group"] + "/" + model.attrib["artifact"] + "/pom.properties"
        require(properties in names, "SG_BUILD_ARTIFACT", "主产物缺少对应 Maven 坐标")
        observed = dict(line.split("=", 1) for line in artifact.read(properties).decode("utf-8").splitlines()
                        if line and not line.startswith("#") and "=" in line)
        require(observed == {"groupId": model.attrib["group"], "artifactId": model.attrib["artifact"], "version": model.attrib["version"]},
                "SG_BUILD_ARTIFACT", "主产物坐标不符")
    return {"path": str(path.resolve()), "sha256": digest(path), "classes": sorted(classes), "coordinates": observed}


def probe(args):
    before_environment = calibrate_wrapper.environment(args)
    before_configuration = configuration()
    contract, contract_hash, required_tests = contract_input(args.contract)
    project, inputs = calibrate_wrapper.validate_project(args.project)
    calibrate_maven.validate_model(project)
    repository = pathlib.Path(args.repository).resolve()
    require(not repository.is_relative_to(project), "SG_BUILD_INPUT", "依赖目录须在工程外")
    with tempfile.TemporaryDirectory(prefix="domain-forge-wrapper-lifecycle-") as raw:
        temporary = pathlib.Path(raw).resolve()
        cache = temporary / "cache"
        cache.mkdir()
        boot = calibrate_wrapper.bootstrap(project, cache)
        document, execution = execute_verify(project, pathlib.Path(boot["home"]), repository, temporary, cache)
        try:
            java_version = re.search(r"^Java version: ([^,]+),", boot["stdout"], re.M)
            core = pathlib.Path(boot["home"]) / "lib" / ("maven-core-" + calibrate_maven.baseline()["version"] + ".jar")
            require(pathlib.Path(document.attrib["mavenHome"]).resolve() == pathlib.Path(boot["home"]) and
                    java_version and document.attrib["javaVersion"] == java_version[1] and digest(core) == boot["core_sha256"],
                    "SG_WRAPPER_RUNTIME", "实际 verify 运行时或下载核心偏离")
            goals = lifecycle_goals(document, project)
            compilations = calibrate_maven.compilation_inventory(document, project, inputs, repository)
            compilation_target(compilations)
            dependencies, plugin_dependencies, constraints, plugin_constraints, artifacts = versions_and_artifacts(document, repository)
            tests = test_results(project, compilations, required_tests)
            artifact = packaged_artifact(document, project, compilations)
            require(inputs == calibrate_wrapper.validate_wrapper(project) and contract_hash == digest(contract) and
                    before_configuration == configuration() and before_environment == calibrate_wrapper.environment(args),
                    "SG_INPUT_CHANGED", "执行期间工程、测试合同、工具或环境变化")
            require(all(digest(pathlib.Path(name)) == checksum for name, checksum in artifacts.items()),
                    "SG_INPUT_CHANGED", "校验期间实际依赖对象变化")
        except ProbeFailure as error:
            error.diagnostic.update(execution)
            raise
    return {"ok": True, "proof_scope": "isolated-wrapper-verify-lifecycle-only", **execution,
            "product_build_proven": False, "publication_proven": False, "human_acceptance": False,
            "compile_release": baseline()["java_release"],
            "distribution_core_sha256": boot["core_sha256"], "compilations": compilations, "goals": goals,
            "resolved_dependencies": dependencies, "resolved_plugin_dependencies": plugin_dependencies,
            "resolved_project_constraints": constraints,
            "resolved_plugin_constraints": plugin_constraints,
            "tests": tests, "artifact": artifact, "inputs": inputs, "contract": {"path": str(contract), "sha256": contract_hash},
            "configuration": before_configuration, "environment": before_environment, "diagnostics": []}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--environment", action="store_true")
    parser.add_argument("--contract")
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    parser.add_argument("--wrapper", default=os.environ.get("DOMAIN_FORGE_GATE_WRAPPER"))
    args = parser.parse_args()
    try:
        report = {"ok": True, "proof_scope": "lifecycle-environment-only", "environment": environment(args), "diagnostics": []} if args.environment else probe(args)
    except ProbeFailure as error:
        report = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        report = {"ok": False, "diagnostics": [{"code": "SG_BUILD_INPUT", "message": type(error).__name__}]}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
