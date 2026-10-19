"""实际运行固定工具及已确认数值基线；不推断源码用途、不产生人工批准。"""

import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET


HERE = pathlib.Path(__file__).resolve().parent


class ProbeFailure(Exception):
    """工具、输入及执行缺口保持明确拒绝，不转换为空扫描成功。"""

    def __init__(self, code, message):
        self.diagnostic = {"code": code, "message": message}


def require(condition, code, message):
    """执行前提不成立时保留稳定诊断。"""
    if not condition:
        raise ProbeFailure(code, message)


def digest(path):
    """读取输入原始字节用于版本与执行前后核对。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def complexity_baseline():
    """唯一配置缺失或含未知／重复字段时拒绝，不回退到工具默认值。"""
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "SG_BASELINE_CONFIG", "复杂度配置含重复字段")
            result[key] = value
        return result

    path = HERE / "complexity.json"
    try:
        require(path.is_file() and not path.is_symlink(), "SG_BASELINE_CONFIG", "复杂度基线缺失或为符号链接")
        baseline = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeError, ValueError) as error:
        raise ProbeFailure("SG_BASELINE_CONFIG", "无法读取复杂度基线") from error
    require(isinstance(baseline, dict) and set(baseline) ==
            {"schema", "decision_ref", "decision_source", "hard_limits", "advisory"},
            "SG_BASELINE_CONFIG", "复杂度基线字段不完整或未知")
    require(type(baseline["schema"]) is int and baseline["schema"] == 1 and
            all(isinstance(baseline[key], str) and baseline[key].strip() for key in ("decision_ref", "decision_source")) and
            baseline["advisory"] == ["MethodLength"], "SG_BASELINE_CONFIG", "复杂度基线版本、来源或辅助指标无效")
    limits = baseline["hard_limits"]
    require(isinstance(limits, dict) and set(limits) == {"production", "test"},
            "SG_BASELINE_CONFIG", "生产与测试须分别声明完整基线")
    for maxima in limits.values():
        require(isinstance(maxima, dict) and set(maxima) == {"CyclomaticComplexity", "ExecutableStatementCount"} and
                all(type(value) is int and value > 0 for value in maxima.values()),
                "SG_BASELINE_CONFIG", "两项硬指标须声明正整数上限")
    return baseline


def prepare(directory):
    """只在显式仓库外目录获取固定公开发行；已有损坏文件保留诊断，不覆盖。"""
    require(directory, "SG_TOOL_INPUT", "准备工具需要显式仓库外目录")
    target = pathlib.Path(directory).resolve()
    require(not target.is_relative_to(HERE.parents[1]), "SG_TOOL_INPUT", "工具制品不得写入项目输入或 Git 元数据")
    lock = json.loads((HERE / "tools.lock.json").read_text(encoding="utf-8"))
    target.mkdir(exist_ok=True)
    allowed = {item["file"] for item in lock["tools"].values()}
    require(all(item.name in allowed and item.is_file() and not item.is_symlink() for item in target.iterdir()),
            "SG_TOOL_INPUT", "仅接受专用空目录或已核对工具目录，不写入其他用户数据")
    for definition in lock["tools"].values():
        artifact = target / definition["file"]
        require(not artifact.exists() or digest(artifact) == definition["sha256"],
                "SG_TOOL_INPUT", f"已有工具校验失败，保留原文件：{artifact.name}")
    downloaded = []
    for definition in lock["tools"].values():
        artifact = target / definition["file"]
        if artifact.exists():
            continue
        with tempfile.NamedTemporaryFile(dir=target, prefix=".download-") as temporary:
            with urllib.request.urlopen(definition["url"], timeout=30) as response:
                count = 0
                for chunk in iter(lambda: response.read(65536), b""):
                    count += len(chunk)
                    require(count <= 64 * 1024 * 1024, "SG_TOOL_INPUT", "工具发行超过校准下载上限")
                    temporary.write(chunk)
            temporary.flush()
            require(digest(pathlib.Path(temporary.name)) == definition["sha256"], "SG_TOOL_INPUT", "下载工具校验失败，不接纳发行")
            os.link(temporary.name, artifact)
        downloaded.append(artifact.name)
    _, environment = toolchain(str(target))
    return {"ok": True, "proof_scope": "pinned-tool-preparation-only", "downloaded": downloaded,
            "environment": environment, "diagnostics": []}


def execute(argv):
    """使用固定参数数组，不使用 shell 或接受输入携带的工具命令。"""
    try:
        return subprocess.run(argv, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ProbeFailure("SG_TOOL_FAILURE", type(error).__name__) from error


def toolchain(directory):
    """核对官方固定发行字节与实际 Java；此处只固定校准环境，不选择产品基线。"""
    lock = json.loads((HERE / "tools.lock.json").read_text(encoding="utf-8"))
    require(directory, "SG_TOOL_INPUT", "显式指定官方固定工具目录，缺失时不得跳过")
    paths = {}
    for name, definition in lock["tools"].items():
        path = pathlib.Path(directory) / definition["file"]
        require(path.is_file() and not path.is_symlink() and digest(path) == definition["sha256"],
                "SG_TOOL_INPUT", f"工具缺失、符号链接或校验值不符：{name}")
        paths[name] = str(path.resolve())
    java = execute(["java", "--version"])
    feature = re.search(r"^(?:openjdk|java) ([0-9]+)[. ]", java.stdout)
    require(java.returncode == 0 and feature and int(feature.group(1)) == lock["java_feature"],
            "SG_RUNTIME", f"本轮隔离校准只验证 Java {lock['java_feature']}；其他运行时须先补齐验证")
    compiler = execute(["javac", "--version"])
    require(compiler.returncode == 0 and re.match(rf"javac {lock['java_feature']}[. ]", compiler.stdout),
            "SG_RUNTIME", "实际编译器与校准语言范围不一致")
    return paths, {"java": java.stdout.strip(), "javac": compiler.stdout.strip(),
                   "tools": lock["tools"], "validation_only": True}


def syntax_inventory(paths, sources):
    """复用固定解析器取得完整文件清单，不以路径猜测包或实现。"""
    lock = json.loads((HERE / "tools.lock.json").read_text(encoding="utf-8"))
    require(lock["preview"] is False, "SG_RUNTIME", "校准尚未验证预览语法，不能默认准入")
    with tempfile.TemporaryDirectory(prefix="domain-forge-metric-inventory-") as temporary:
        compiled = execute(["javac", "--release", str(lock["source_release"]), "-proc:none", "-encoding", "UTF-8",
                            "-cp", paths["checkstyle"], "-d", temporary, str(HERE / "MetricInventory.java")])
        require(compiled.returncode == 0, "SG_TOOL_FAILURE", "计量覆盖适配器实际编译失败")
        process = execute(["java", "-cp", os.pathsep.join([temporary, paths["checkstyle"]]),
                           "MetricInventory", *map(str, sources)])
    require(process.returncode == 0 and not process.stderr, "SG_TOOL_FAILURE", "实际语法树清单执行失败")
    try:
        output = ET.fromstring(process.stdout)
        files = output.findall("file")
        require(output.tag == "inventory" and len(files) == len(sources) and
                {item.attrib["name"] for item in files} == set(map(str, sources)),
                "SG_SCAN_COVERAGE", "实现清单与显式源码不一致")
        require(all("package" in file.attrib for file in files), "SG_SCAN_COVERAGE", "语法树清单缺少实际包声明")
        return files
    except (ET.ParseError, KeyError, ValueError) as error:
        raise ProbeFailure("SG_TOOL_FAILURE", "实际语法树清单无效") from error


def metric_inventory(paths, sources):
    """列出真实实现；不从已有违规行号反推扫描覆盖。"""
    try:
        return [{"file": file.attrib["name"], "kind": item.attrib["kind"],
                 "line": int(item.attrib["line"]), "column": int(item.attrib["column"]),
                 "complexity": int(item.attrib["complexity"])}
                for file in syntax_inventory(paths, sources) for item in file.findall("implementation")]
    except (KeyError, ValueError) as error:
        raise ProbeFailure("SG_TOOL_FAILURE", "实际实现清单无效") from error


def metric_coverage(implementations, measurements):
    """逐个实现核对两项硬指标；一个已计量方法不能代表其他实现。"""
    keys = {(item["file"], item["line"], item["column"], item["rule"]) for item in measurements}
    missing = [dict(item, rule=rule) for item in implementations
               for rule in ("CyclomaticComplexity", "ExecutableStatementCount")
               if (item["file"], item["line"], item["column"], rule) not in keys]
    return {"complete": not missing, "implementations": implementations, "missing": missing}


def checkstyle(paths, sources, action, purpose):
    """复用真实解析器；零阈值仅采集计量，不将工具默认值当成项目上限。"""
    config = {"comments": "comments.xml", "method-presence": "methods.xml", "measure": "measure.xml"}[action]
    with tempfile.TemporaryDirectory(prefix="domain-forge-probe-config-") as temporary:
        properties = pathlib.Path(temporary) / "scope.properties"
        properties.write_text("javadoc.scope=" + ("anoninner" if purpose == "production" else "nothing") + "\n", encoding="utf-8")
        process = execute(["java", "-Duser.language=en", "-Duser.country=US", "-jar", paths["checkstyle"],
                           "-c", str(HERE / config), "-p", str(properties), "-f", "xml", *map(str, sources)])
    try:
        output = ET.fromstring(process.stdout)
    except ET.ParseError as error:
        raise ProbeFailure("SG_TOOL_FAILURE", "Checkstyle 未生成有效 XML") from error
    require(output.tag == "checkstyle" and {str(path) for path in sources} ==
            {str(pathlib.Path(item.attrib["name"]).resolve()) for item in output.findall("file")},
            "SG_SCAN_COVERAGE", "Checkstyle 实际扫描与显式输入不一致")
    diagnostics = []
    for file in output.findall("file"):
        for error in file.findall("error"):
            rule = error.attrib.get("source", "").rsplit(".", 1)[-1].removesuffix("Check")
            diagnostics.append({"code": "SG_RULE", "file": str(pathlib.Path(file.attrib["name"]).resolve()),
                                "line": int(error.attrib["line"]), "column": int(error.attrib.get("column", "1")),
                                "rule": rule, "message": error.attrib["message"]})
    allowed = {"MissingJavadocType", "InvalidJavadocPosition", "SummaryJavadoc", "MissingJavadocMethod", "CyclomaticComplexity", "MethodLength", "ExecutableStatementCount"}
    require(all(item["rule"] in allowed for item in diagnostics) and
            ((process.returncode == 0 and not diagnostics) or (process.returncode > 0 and diagnostics)),
            "SG_TOOL_FAILURE", "Checkstyle 执行失败或出现非目标解析诊断")
    return diagnostics


def probe(args):
    """公开入口只证明列出的对象与检查项，不产生用途、合同、风险或语义批准。"""
    if args.action == "limits":
        require(args.complexity_limit > 0 and args.statement_limit > 0, "SG_LIMIT_INPUT", "候选上限必须显式提供正整数")
    baseline = complexity_baseline() if args.action == "complexity" else None
    require(args.sources, "SG_SOURCE_INPUT", "没有显式 Java 输入，不能执行空扫描")
    sources = []
    for raw in args.sources:
        path = pathlib.Path(raw)
        require(path.is_file() and path.suffix == ".java" and not path.is_symlink(), "SG_SOURCE_INPUT", f"无效 Java 输入：{path}")
        sources.append(path.resolve())
    require(len(set(sources)) == len(sources), "SG_SOURCE_INPUT", "源码输入重复")
    before = {str(path): digest(path) for path in sources}
    config_before = {name: digest(HERE / name) for name in ("tools.lock.json", "comments.xml", "methods.xml", "measure.xml", "MetricInventory.java", "calibrate_source.py")}
    if baseline is not None:
        config_before["complexity.json"] = digest(HERE / "complexity.json")
    paths, environment = toolchain(args.tools)
    result = {"ok": True, "proof_scope": "explicit-source-tool-calibration-only", "semantic_review_proven": False,
              "action": args.action, "scanned": list(before), "inputs": before, "configuration": config_before,
              "environment": environment, "diagnostics": []}
    if args.action == "format":
        process = execute(["java", "-jar", paths["formatter"], "--dry-run", "--set-exit-if-changed", *map(str, sources)])
        changed = process.stdout.splitlines()
        require(process.returncode in (0, 1) and not process.stderr and set(changed).issubset(before),
                "SG_TOOL_FAILURE", "格式工具出现解析或执行失败")
        require((process.returncode == 0) == (not changed), "SG_TOOL_FAILURE", "格式检查结果不一致")
        result["diagnostics"] = [{"code": "SG_FORMAT", "file": path, "message": "偏离固定格式基线"} for path in changed]
    else:
        action = "measure" if args.action in ("limits", "complexity") else args.action
        diagnostics = checkstyle(paths, sources, action, getattr(args, "purpose", "production"))
        if action == "measure":
            require(diagnostics, "SG_EMPTY_METRICS", "未计量到实现；不能证明指标覆盖")
            measurements = []
            for item in diagnostics:
                require(item["rule"] in ("CyclomaticComplexity", "MethodLength", "ExecutableStatementCount"), "SG_TOOL_FAILURE", "计量出现非目标失败")
                measured = re.search(r"(?:Complexity is|length is|statement count is) ([0-9]+)", item["message"])
                require(measured, "SG_TOOL_FAILURE", "无法核对工具计量格式")
                measurements.append(dict(item, actual=int(measured.group(1))))
            implementations = metric_inventory(paths, sources)
            require(implementations, "SG_EMPTY_METRICS", "只有声明，没有实际可计量实现")
            result["tool_measurements"] = measurements
            result["tool_coverage"] = metric_coverage(implementations, measurements)
            positions = {(item["file"], item["line"], item["column"]) for item in implementations}
            measurements = [item for item in measurements if item["rule"] != "CyclomaticComplexity"
                            and (item["file"], item["line"], item["column"]) in positions]
            measurements.extend(dict(item, code="SG_MEASUREMENT", rule="CyclomaticComplexity",
                                     actual=item["complexity"], metric_source="checkstyle-ast-scoped-adapter")
                                for item in implementations)
            result["measurements"] = measurements
            result["coverage"] = metric_coverage(implementations, measurements)
            result["thresholds_fixed"] = False
            result["line_measurement_basis"] = "provided-source-lines; formatter-proof-is-separate"
            if args.action in ("limits", "complexity"):
                if baseline is None:
                    maxima = {"CyclomaticComplexity": args.complexity_limit,
                              "ExecutableStatementCount": args.statement_limit}
                    result["proof_scope"] = "explicit-candidate-limits-only"
                    result["candidate_limits"] = {"purpose": args.purpose, "max": maxima}
                else:
                    maxima = baseline["hard_limits"][args.purpose]
                    result.update(proof_scope="approved-complexity-subset-only", thresholds_fixed=True,
                                  limits={"purpose": args.purpose, "max": maxima},
                                  decision_ref=baseline["decision_ref"], human_acceptance=False,
                                  purpose_basis="explicit-input-not-inferred")
                result["advisory"] = [item for item in measurements if item["rule"] == "MethodLength"]
                result["diagnostics"] = [dict(item, code="SG_LIMIT", max=maxima[item["rule"]])
                                         for item in measurements
                                         if item["rule"] in maxima and item["actual"] > maxima[item["rule"]]]
                result["diagnostics"].extend(dict(item, code="SG_METRIC_COVERAGE", message="实际实现缺少所选硬指标计量")
                                             for item in result["coverage"]["missing"])
        else:
            result["diagnostics"] = diagnostics
    require(before == {str(path): digest(path) for path in sources}, "SG_INPUT_CHANGED", "工具执行改变源码输入，结果失效")
    require(config_before == {name: digest(HERE / name) for name in config_before}, "SG_INPUT_CHANGED", "执行期间配置变化，结果失效")
    _, after_environment = toolchain(args.tools)
    require(environment == after_environment, "SG_INPUT_CHANGED", "执行期间工具或运行时变化，结果失效")
    result["ok"] = not result["diagnostics"]
    return result


def main():
    """返回有证明范围的 JSON；空输入、工具缺失和未知执行失败不能放行。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tools", default=os.environ.get("DOMAIN_FORGE_GATE_TOOLS"))
    actions = parser.add_subparsers(dest="action", required=True)
    actions.add_parser("environment")
    actions.add_parser("prepare")
    for action in ("comments", "method-presence", "format", "measure", "limits", "complexity"):
        option = actions.add_parser(action)
        if action in ("comments", "limits", "complexity"):
            option.add_argument("--purpose", choices=("production", "test"), required=True)
        if action == "limits":
            option.add_argument("--complexity-limit", type=int, required=True)
            option.add_argument("--statement-limit", type=int, required=True)
        option.add_argument("sources", nargs="*")
    args = parser.parse_args()
    try:
        if args.action == "environment":
            _, environment = toolchain(args.tools)
            result = {"ok": True, "environment": environment, "diagnostics": [], "proof_scope": "tool-environment-only"}
        elif args.action == "prepare":
            result = prepare(args.tools)
        else:
            result = probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
