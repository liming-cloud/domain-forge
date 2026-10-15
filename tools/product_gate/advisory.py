"""在实际项目的依赖图上执行原生告警，工具错误和漏洞告警分别报告。"""

import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

from environment import command, copy_project, digest, require
import calibrate_advisory


def dependencies(items):
    """原生工具会合并同产品组件；相关依赖仍须逐项保留散列覆盖。"""
    for item in items:
        yield item
        yield from dependencies(item.get("relatedDependencies", []))


def configure(project):
    document = ET.parse(project / "pom.xml")
    ns = "{http://maven.apache.org/POM/4.0.0}"
    plugin = calibrate_advisory.plugin()
    version = plugin.findtext("version")
    for element in plugin.iter():
        element.tag = ns + element.tag
    document.find("./{*}build/{*}plugins").append(plugin)
    ET.register_namespace("", ns[1:-1])
    document.write(project / "pom.xml", encoding="UTF-8", xml_declaration=True)
    return version


def prepare(project, data, argv, env, output):
    """只在显式准备命令中更新原生资料，不将准备成功解释为依赖扫描通过。"""
    version = configure(project)
    data.mkdir(parents=True, exist_ok=True)
    require(not data.is_symlink(), "PG_ADVISORY_DATA", "原生资料目录不能是符号链接")
    execution = command([*argv, "-DdataDirectory=" + str(data),
                         "org.owasp:dependency-check-maven:" + version + ":update-only"],
                        project, output / "advisory-preparation.log", 2700, env, preparation=True)
    require((data / "odc.mv.db").is_file(), "PG_ADVISORY_DATA", "原生准备没有生成数据库")
    return {"ok": True, "proof_scope": "dependency-data-preparation-only", "execution": execution,
            "data_sha256": digest(data / "odc.mv.db"), "product_scan_proven": False, "human_acceptance": False}


def scan(root, workspace, inputs, repository, data, argv, env, output, observed):
    require(data and (Path(data) / "odc.mv.db").is_file(), "PG_ADVISORY_DATA", "缺少原生漏洞资料，扫描未完成")
    data = Path(data)
    require(not data.is_symlink() and not (data / "odc.mv.db").is_symlink(), "PG_ADVISORY_DATA", "资料不能是符号链接")
    data = data.resolve()
    checksum = digest(data / "odc.mv.db")
    isolated_data = workspace / "advisory-data"
    isolated_data.mkdir()
    shutil.copy2(data / "odc.mv.db", isolated_data / "odc.mv.db")
    project = copy_project(root, workspace / "advisory-project", inputs)
    version = configure(project)
    # 仅增加已确认扫描插件；直接/传递依赖仍来自同一实际工程，报告逐项绑定字节。
    execution = command([str(project / "mvnw"), *argv[1:], "-DdataDirectory=" + str(isolated_data),
                         "-DautoUpdate=false", "-DretireJsAnalyzerEnabled=false",
                         "org.owasp:dependency-check-maven:" + version + ":check"],
                        project, output / "advisory.log", 900, env)
    reports = {kind: project / ("target/dependency-check-report." + kind) for kind in ("html", "json")}
    require(all(path.is_file() and path.stat().st_size for path in reports.values()),
            "PG_ADVISORY_REPORT", "缺少本次原生非空报告")
    report = json.loads(reports["json"].read_text())
    require(report.get("scanInfo", {}).get("engineVersion") == version and report.get("dependencies"),
            "PG_ADVISORY_REPORT", "原生版本不符或扫描范围为空")
    covered = coverage(report, observed)
    require(checksum == digest(data / "odc.mv.db"), "PG_INPUT_CHANGED", "原始漏洞资料执行期间变化")
    for kind, path in reports.items():
        target = output / path.name
        shutil.copy2(path, target)
        reports[kind] = target
    return {"ok": True, "execution": execution, "engine_version": version, "data_sha256": checksum,
            "derived_pom_sha256": digest(project / "pom.xml"), "source_pom_sha256": inputs["pom.xml"],
            "plugin_configuration_sha256": digest(calibrate_advisory.HERE / "maven-dependency-check.xml"),
            "reports": {kind: {"path": str(path), "sha256": digest(path)} for kind, path in reports.items()},
            "covered_dependency_hashes": sorted(covered),
            "findings": [{"component": item.get("fileName"), "sha256": item.get("sha256"),
                          "vulnerabilities": item.get("vulnerabilities", [])}
                         for item in report["dependencies"] if item.get("vulnerabilities")]}


def coverage(report, observed):
    covered = {item["sha256"] for item in dependencies(report["dependencies"]) if "sha256" in item}
    required = {item["sha256"] for item in observed["dependencies"] if item["extension"] == "jar"}
    require(required <= covered, "PG_ADVISORY_COVERAGE", "原生报告遗漏实际生产或测试依赖")
    return covered
