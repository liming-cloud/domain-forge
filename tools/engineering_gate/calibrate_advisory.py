"""薄接入固定成熟 Maven 告警插件；原生准备与隔离扫描分开，不自研漏洞策略。"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

import calibrate_maven
from calibrate_source import HERE, ProbeFailure, digest, require


CONFIG = HERE / "maven-dependency-check.xml"


def configuration():
    return {name: digest(HERE / name) for name in ("calibrate_advisory.py", "maven-dependency-check.xml",
            "calibrate_maven.py", "calibrate_source.py", "maven-scope.lock.json")}


def plugin():
    return ET.parse(CONFIG).getroot()


def directory(raw):
    path = Path(raw or "")
    require(raw and not path.is_symlink() and not path.resolve().is_relative_to(HERE.parents[1]),
            "SG_ADVISORY_INPUT", "必须使用显式仓库外原生资料或依赖专用目录")
    require(path.is_dir(), "SG_ADVISORY_INPUT", "专用目录尚未准备")
    require(not any(item.is_symlink() for item in path.rglob("*")),
            "SG_ADVISORY_INPUT", "原生缓存目录不能含符号链接")
    return path.resolve()


def runtime(args):
    home, observed = calibrate_maven.runtime(args.maven_home)
    repository = directory(args.repository)
    return home, repository, dict(observed, home=str(home), repository=str(repository))


def data_hash(data):
    database = data / "odc.mv.db"
    return digest(database) if database.is_file() and not database.is_symlink() else None


def environment(args):
    """缺少原生资料或已准备插件就失败，不下载后伪称原环境可用。"""
    _, repository, observed = runtime(args)
    data = directory(args.data)
    checksum = data_hash(data)
    require(checksum, "SG_ADVISORY_DATA", "缺少原生 Dependency-Check 资料，检查未完成")
    version = plugin().findtext("version")
    artifact = repository / "org/owasp/dependency-check-maven" / version / ("dependency-check-maven-" + version + ".jar")
    require(artifact.is_file() and not artifact.is_symlink(), "SG_ADVISORY_DATA", "固定原生插件尚未准备")
    return dict(observed, plugin_version=version, plugin_sha256=digest(artifact), data=str(data), data_sha256=checksum)


def normalized(node):
    return (node.tag.rsplit("}", 1)[-1], (node.text or "").strip(), tuple(normalized(item) for item in node))


def project_input(raw):
    project, inputs = calibrate_maven.validate_project(Path(raw or ""))
    plugins = ET.parse(project / "pom.xml").findall("./{*}build/{*}plugins/{*}plugin")
    matches = [item for item in plugins if item.findtext("{*}groupId") == "org.owasp" and
               item.findtext("{*}artifactId") == "dependency-check-maven"]
    require(len(matches) == 1 and normalized(matches[0]) == normalized(plugin()),
            "SG_ADVISORY_CONFIG", "原生插件必须使用唯一固定版本、基本范围及告警配置")
    return project, inputs


def native(home, repository, project, data, operation, log):
    """只调用原生 update-only 或 check；普通扫描隔离更新网络，不接受任意参数。"""
    with tempfile.TemporaryDirectory(prefix="domain-forge-advisory-settings-") as raw:
        settings = Path(raw) / "settings.xml"
        settings.write_text('''<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><mirrors>
<mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror>
</mirrors></settings>''', encoding="utf-8")
        argv = [str(home / "bin/mvn"), "--batch-mode", "--no-transfer-progress", "--settings", str(settings),
                "-Dmaven.repo.local=" + str(repository), "-DdataDirectory=" + str(data)]
        if operation == "check":
            argv.extend(["-DautoUpdate=false", "-DretireJsAnalyzerEnabled=false"])
        argv.append("org.owasp:dependency-check-maven:" + plugin().findtext("version") + ":" + operation)
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open("w", encoding="utf-8") as output:
            try:
                result = subprocess.run(argv, cwd=project, env=dict(os.environ, MAVEN_SKIP_RC="true"),
                                        stdout=output, stderr=subprocess.STDOUT,
                                        timeout=1500 if operation == "update-only" else 30)
                code = result.returncode
            except subprocess.TimeoutExpired:
                code = None
    execution = {"command": argv, "exit_code": code, "log": str(log)}
    if code != 0:
        failure = ProbeFailure("SG_ADVISORY_PREPARE" if operation == "update-only" else "SG_ADVISORY_SCAN",
                               "原生分析失败或超时，检查未完成；保留实际日志")
        failure.diagnostic.update(execution)
        raise failure
    return execution


def prepare(args):
    """原生更新指定专用缓存，不清理资料；准备成功不证明产品已扫描。"""
    before = configuration()
    data = directory(args.prepare)
    home, repository, observed = runtime(args)
    with tempfile.TemporaryDirectory(prefix="domain-forge-advisory-update-") as raw:
        project = Path(raw)
        (project / "pom.xml").write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>probe</groupId><artifactId>native-update</artifactId><version>1.0.0</version>
<build><plugins>''' + CONFIG.read_text(encoding="utf-8") + "</plugins></build></project>", encoding="utf-8")
        execution = native(home, repository, project, data, "update-only", data / "preparation.log")
    require(data_hash(data), "SG_ADVISORY_DATA", "原生准备没有形成资料，不生成可用结论")
    require(before == configuration() and observed == runtime(args)[2], "SG_INPUT_CHANGED", "原生准备期间工具或运行时变化")
    return {"ok": True, "proof_scope": "native-advisory-data-preparation-only", **execution,
            "data": str(data), "data_sha256": data_hash(data), "native_update_completed": True,
            "product_scan_proven": False, "human_acceptance": False, "diagnostics": []}


def scan(args):
    before = configuration()
    project, inputs = project_input(args.project)
    home, repository, observed = runtime(args)
    data = directory(args.data)
    checksum = data_hash(data)
    with tempfile.TemporaryDirectory(prefix="domain-forge-advisory-copy-") as raw:
        isolated = Path(raw)
        if checksum:
            shutil.copy2(data / "odc.mv.db", isolated / "odc.mv.db")
        execution = native(home, repository, project, isolated, "check", project / "target/advisory-maven.log")
    reports = {}
    for format in ("html", "json"):
        path = project / ("target/dependency-check-report." + format)
        require(path.is_file() and not path.is_symlink() and path.stat().st_size > 0,
                "SG_ADVISORY_REPORT", "没有本次原生非空报告：" + format)
        reports[format] = {"path": str(path), "sha256": digest(path)}
    report = json.loads(Path(reports["json"]["path"]).read_text(encoding="utf-8"))
    require(report.get("scanInfo", {}).get("engineVersion") == plugin().findtext("version") and
            isinstance(report.get("dependencies"), list) and report["dependencies"],
            "SG_ADVISORY_REPORT", "原生版本不符或扫描范围为空，不能认为没有风险")
    dependencies = [{"file": item["fileName"], "sha256": item["sha256"],
                     "findings": [{"id": finding["name"], "severity": finding["severity"]}
                                  for finding in item.get("vulnerabilities", [])]} for item in report["dependencies"]]
    require(inputs == calibrate_maven.project_inputs(project) and before == configuration() and
            checksum == data_hash(data) and observed == runtime(args)[2],
            "SG_INPUT_CHANGED", "执行期间维护输入、资料或运行时变化")
    return {"ok": True, "proof_scope": "isolated-native-maven-advisory-only", **execution,
            "engine_version": report["scanInfo"]["engineVersion"], "reports": reports,
            "dependencies": dependencies, "data_sources": report["scanInfo"].get("dataSource", []),
            "inputs": inputs, "configuration": before, "data_sha256": checksum,
            "product_scan_proven": False, "online_update_proven": False, "human_acceptance": False, "diagnostics": []}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--prepare", metavar="EXTERNAL_NATIVE_CACHE")
    operation.add_argument("--project")
    operation.add_argument("--environment", action="store_true")
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_ADVISORY_REPOSITORY"))
    parser.add_argument("--data", default=os.environ.get("DOMAIN_FORGE_GATE_ADVISORY_DATA"))
    args = parser.parse_args()
    try:
        if args.prepare:
            report = prepare(args)
        elif args.environment:
            report = {"ok": True, "proof_scope": "native-advisory-environment-only", "environment": environment(args), "diagnostics": []}
        else:
            report = scan(args)
    except ProbeFailure as error:
        report = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        report = {"ok": False, "diagnostics": [{"code": "SG_ADVISORY_INPUT", "message": type(error).__name__}]}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
