"""观察固定 Maven 的真实编译范围；不宣称产品构建、发布或语义用途已经验收。"""

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
import zipfile

from calibrate_source import HERE, ProbeFailure, digest, execute, require


def baseline():
    return json.loads((HERE / "maven-scope.lock.json").read_text(encoding="utf-8"))


def runtime(directory):
    """核对固定 Maven 核心与实际版本；不借用开发机偶然安装的 Maven。"""
    definition = baseline()
    home = pathlib.Path(directory or "")
    core = home / "lib" / ("maven-core-" + definition["version"] + ".jar")
    require(directory and home.is_dir() and not home.is_symlink() and core.is_file() and
            not core.is_symlink() and digest(core) == definition["core_sha256"],
            "SG_MAVEN_RUNTIME", "缺少固定 Maven 或核心校验不符")
    require(all(not os.environ.get(key) for key in ("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")),
            "SG_MAVEN_RUNTIME", "校准不接受隐式 JVM 或 Maven 参数；不能悄悄移除后称为原环境")
    result = execute([str(home / "bin/mvn"), "--version"])
    require(result.returncode == 0 and result.stdout.startswith("Apache Maven " + definition["version"] + " "),
            "SG_MAVEN_RUNTIME", "实际 Maven 版本不符")
    require(re.search(r"Java version: 21[.]", result.stdout), "SG_RUNTIME", "Maven 实际运行时须为已校准的 Java 21")
    java = execute(["javac", "--version"])
    require(java.returncode == 0 and java.stdout.startswith("javac 21."), "SG_RUNTIME", "本轮只校准 Java 21")
    return home.resolve(), {"maven": result.stdout.strip(), "javac": java.stdout.strip(), "validation_only": True}


def environment(args):
    """统一入口预检真实 Maven 和已准备插件，不把缺少环境当作测试跳过。"""
    home, observed = runtime(args.maven_home)
    repository = pathlib.Path(args.repository or "")
    require(args.repository and repository.is_dir() and not repository.is_symlink(),
            "SG_BUILD_INPUT", "缺少专用 Maven 依赖目录")
    observed["plugins"] = {}
    for name in ("compiler", "resources"):
        version = baseline()[name + "_version"]
        artifact = repository / "org/apache/maven/plugins" / ("maven-" + name + "-plugin") / version / ("maven-" + name + "-plugin-" + version + ".jar")
        require(artifact.is_file() and not artifact.is_symlink(), "SG_MAVEN_RUNTIME", "固定插件尚未准备，不能跳过真实场景")
        observed["plugins"][name] = {"version": version, "sha256": digest(artifact)}
    observed["home"] = str(home)
    observed["repository"] = str(repository.resolve())
    return {"ok": True, "proof_scope": "maven-scope-environment-only", "environment": observed, "diagnostics": []}


def prepare(directory):
    """准备固定 Maven 和预检必需插件，使用空专用目录，不覆盖已有用户文件。"""
    target = pathlib.Path(directory)
    require(target.is_dir() and not target.is_symlink() and not any(target.iterdir()) and
            not target.resolve().is_relative_to(HERE.parents[1]), "SG_BUILD_INPUT", "准备需要仓库外专用空目录")
    definition = baseline()
    archive = target / "maven.zip"
    with urllib.request.urlopen(definition["url"], timeout=30) as response, archive.open("xb") as output:
        output.write(response.read(16 * 1024 * 1024 + 1))
    require(archive.stat().st_size <= 16 * 1024 * 1024 and
            hashlib.sha512(archive.read_bytes()).hexdigest() == definition["sha512"],
            "SG_MAVEN_RUNTIME", "Maven 分发校验不符，保留文件供诊断")
    with zipfile.ZipFile(archive) as source:
        require(all(not pathlib.PurePosixPath(name).is_absolute() and ".." not in pathlib.PurePosixPath(name).parts
                    for name in source.namelist()), "SG_BUILD_INPUT", "分发目录无效")
        source.extractall(target)
    home = target / ("apache-maven-" + definition["version"])
    (home / "bin/mvn").chmod(0o755)
    repository = target / "repository"
    repository.mkdir()
    runtime(str(home))
    settings = target / "settings.xml"
    settings.write_text('<settings><mirrors><mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf>'
                        '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>', encoding="utf-8")
    command = [str(home / "bin/mvn"), "-B", "-ntp", "-s", str(settings), "-gs", str(settings),
               "-Dmaven.repo.local=" + str(repository.resolve()),
               *["org.apache.maven.plugins:maven-" + name + "-plugin:" + definition[name + "_version"] + ":help"
                 for name in ("resources", "compiler")]]
    log = target / "prepare.log"
    with log.open("w", encoding="utf-8") as output:
        try:
            process = subprocess.run(command, cwd=target, stdout=output, stderr=subprocess.STDOUT, text=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ProbeFailure("SG_MAVEN_RUNTIME", "固定插件准备未完成，保留日志 " + str(log)) from error
    require(process.returncode == 0, "SG_MAVEN_RUNTIME", "固定插件准备失败，保留日志 " + str(log))
    observed = environment(argparse.Namespace(maven_home=str(home), repository=str(repository)))["environment"]
    return {"ok": True, "maven_home": str(home.resolve()), "repository": str(repository.resolve()),
            "environment": observed, "command": command, "log": str(log),
            "proof_scope": "fixed-maven-preparation-only", "diagnostics": []}


def project_inputs(project):
    """只读维护输入；不允许符号链接、已有构建输出或输入漂移被忽略。"""
    result = {}
    for path in sorted(project.rglob("*")):
        if path.is_relative_to(project / "target"):
            continue
        require(not path.is_symlink(), "SG_BUILD_INPUT", "校准工程输入含符号链接")
        if path.is_file():
            result[str(path)] = digest(path)
    return result


def validate_project(project):
    """本切片仅支持仓库外、单项目、默认输出目录；不对未校准构建形式给出通过。"""
    require(project.is_dir() and not project.is_symlink() and not project.resolve().is_relative_to(HERE.parents[1]),
            "SG_BUILD_INPUT", "仅接受显式仓库外隔离工程，不运行未批准的产品研发")
    project = project.resolve()
    require(not (project / "target").exists() and not (project / "target").is_symlink(),
            "SG_DIRTY_BUILD", "需要无构建输出的隔离工程；不删除已有数据")
    require((project / "pom.xml").is_file() and not (project / ".mvn").exists(),
            "SG_BUILD_INPUT", "缺少 POM 或存在尚未校准的隐式 Maven 配置")
    inputs = project_inputs(project)
    validate_model(project)
    return project, inputs


def validate_model(project):
    """编译与完整生命周期共享已校准的单工程模型边界，不复制支持口径。"""
    document = ET.parse(project / "pom.xml")
    unsupported = ("./{*}parent", "./{*}modules", "./{*}profiles", "./{*}build/{*}directory",
                   "./{*}build/{*}outputDirectory", "./{*}build/{*}testOutputDirectory")
    require(not any(document.find(path) is not None for path in unsupported),
            "SG_BUILD_SCOPE", "父工程、聚合、配置集和自定义输出目录留待扩展验证，不作为本切片通过")
    unsupported_properties = {"maven.compiler.fork", "maven.compiler.executable", "maven.compiler.compilerId",
                              "maven.compiler.forceJavacCompilerUse", "maven.compiler.multiReleaseOutput"}
    defaults = {"fork": "false", "compilerId": "javac", "multiReleaseOutput": "false"}
    for setting in document.findall("./{*}properties/*"):
        name = setting.tag.split("}")[-1]
        if name in unsupported_properties:
            require(setting.text is not None and setting.text == defaults.get(name.removeprefix("maven.compiler.")),
                    "SG_BUILD_CLASSPATH", "通过属性替换编译器或输出模式尚未校准，不能称为默认编译路径")
    for plugin in document.findall("./{*}build/{*}plugins/{*}plugin"):
        if plugin.findtext("{*}artifactId") != "maven-compiler-plugin":
            continue
        for configuration in plugin.findall(".//{*}configuration"):
            for setting in configuration:
                name = setting.tag.split("}")[-1]
                if name in {"compilerArgs", "compilerArguments", "testCompilerArguments", "compilerArgument", "testCompilerArgument"}:
                    require(name == "compilerArgs" and list(setting) and
                            all(child.tag.split("}")[-1] == "arg" and child.text == "-g:none" and not list(child) for child in setting),
                            "SG_BUILD_CLASSPATH", "自定义编译参数尚未校准；不能用模型类路径代表覆盖后的实际编译类路径")
                if name in {"compilePath", "testPath", "compilerId", "fork", "executable", "jdkToolchain", "multiReleaseOutput"}:
                    require(setting.text is not None and setting.text == defaults.get(name) and not list(setting),
                            "SG_BUILD_CLASSPATH", "替换编译器、类路径或多版本输出尚未校准")


def observer(home, temporary):
    """复用 Maven 公开 EventSpy 扩展，不解析日志猜测编译用途。"""
    classes = temporary / "observer"
    classes.mkdir()
    result = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp",
                      str(home / "lib/*"), "-d", str(classes), str(HERE / "MavenScopeSpy.java")])
    require(result.returncode == 0, "SG_TOOL_FAILURE", "Maven 范围观察器实际编译失败")
    components = '''<component-set><components><component>
<role>org.apache.maven.eventspy.EventSpy</role><role-hint>domain-forge-scope</role-hint>
<implementation>org.domainforge.gate.MavenScopeSpy</implementation>
</component></components></component-set>'''
    artifact = temporary / "scope-spy.jar"
    with zipfile.ZipFile(artifact, "w") as archive:
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, path.relative_to(classes).as_posix())
        archive.writestr("META-INF/plexus/components.xml", components)
    return artifact


def compile_project(home, project, repository, temporary):
    """真实执行 test-compile，既不跳过失败也不执行产品功能或伪造编译清单。"""
    extension = observer(home, temporary)
    output = temporary / "inventory.xml"
    settings = temporary / "settings.xml"
    settings.write_text('''<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><mirrors>
<mirror><id>isolated-public-central</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror>
</mirrors></settings>''', encoding="utf-8")
    argv = [str(home / "bin/mvn"), "--batch-mode", "--no-transfer-progress", "--settings", str(settings),
            "-Dmaven.repo.local=" + str(repository), "-Dmaven.ext.class.path=" + str(extension),
            "-Ddomainforge.scope.output=" + str(output), "test-compile"]
    try:
        result = subprocess.run(argv, cwd=project, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ProbeFailure("SG_MAVEN_FAILURE", "真实 Maven 执行未完成：" + type(error).__name__) from error
    log = project / "target/maven-scope.log"
    log.parent.mkdir(exist_ok=True)
    log.write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != 0:
        error = ProbeFailure("SG_MAVEN_FAILURE", "真实 Maven 执行失败，不作为范围通过；原生日志单独保留")
        error.diagnostic.update(exit_code=result.returncode, log=str(log))
        raise error
    require(output.is_file(), "SG_BUILD_OBSERVER", "实际执行没有生成观察结果，不能补造报告")
    return ET.parse(output).getroot(), argv


def listed_files(path, base=None):
    require(path.is_file() and not path.is_symlink(), "SG_SOURCE_COVERAGE", "缺少实际编译输入或产物清单")
    names = path.read_text(encoding="utf-8").splitlines()
    require(names and len(names) == len(set(names)), "SG_SOURCE_COVERAGE", "实际编译清单为空或重复")
    files = [base / name if base else pathlib.Path(name) for name in names]
    require(all(path.is_file() and not path.is_symlink() for path in files), "SG_SOURCE_COVERAGE", "编译清单包含缺失对象")
    return sorted(str(path.resolve()) for path in files)


def exact_coverage(expected, actual, code, message):
    """诊断列出具体遗漏和多余对象，使拒绝结果能用于整改，而非只有失败标签。"""
    if expected != actual:
        error = ProbeFailure(code, message)
        error.diagnostic.update(missing=sorted(expected - actual), unexpected=sorted(actual - expected))
        raise error


def compilation_classpath(item, project, repository):
    """核对同次成功事件的默认类路径及解析对象；不接收调用方提供的替代路径。"""
    paths = [pathlib.Path(node.attrib["path"]) for node in item.findall("classpath")]
    dependencies = [dict(node.attrib) for node in item.findall("classpathDependency")]
    directories = [project / "target/classes"]
    if item.attrib["goal"] == "testCompile":
        directories.insert(0, project / "target/test-classes")
    expected = [*directories, *[pathlib.Path(node["file"]) for node in dependencies if node["addedToClasspath"] == "true" and
                                (item.attrib["goal"] == "testCompile" or node["scope"] in {"compile", "provided", "system"})]]
    require(paths == expected and len(paths) == len(set(paths)), "SG_BUILD_CLASSPATH", "实际类路径缺失、重复或与同次原生解析用途不符")
    require(all(path.is_dir() and not path.is_symlink() for path in directories),
            "SG_BUILD_CLASSPATH", "实际类路径缺少构建输出目录")
    for path in paths[len(directories):]:
        require(path.is_file() and not path.is_symlink() and path.resolve() == path and path.is_relative_to(repository),
                "SG_BUILD_CLASSPATH", "实际依赖类路径不是专用仓库中的普通文件")
    return list(map(str, paths))


def compilation_inventory(document, project, inputs, repository):
    """核对实际根、编译器清单和全部类产物，排除漏编译、跳过与陈旧清单。"""
    projects = document.findall("project")
    require(document.tag == "inventory" and len(projects) == 1 and
            pathlib.Path(projects[0].attrib["path"]).resolve() == project and
            projects[0].attrib["packaging"] == "jar" and
            pathlib.Path(projects[0].attrib["build"]).resolve() == project / "target",
            "SG_BUILD_SCOPE", "实际 Maven 项目或输出范围超出已校准边界")
    dependencies = [*projects[0].findall("declaredDependency"), *document.findall("compilation/classpathDependency")]
    for dependency in dependencies:
        if dependency.get("scope") == "system":
            error = ProbeFailure("SG_BUILD_DEPENDENCY_PATH", "实际有效依赖使用机器 JAR 路径，不能作为受支持交付")
            error.diagnostic["dependency"] = {field: dependency.get(field) for field in ("group", "artifact", "version", "scope")}
            raise error
    repository = pathlib.Path(repository).resolve()
    require(pathlib.Path(document.attrib["repository"]).resolve() == repository,
            "SG_BUILD_CLASSPATH", "实际解析仓库与显式专用仓库不一致")
    compilations, assigned = [], set()
    for item in document.findall("compilation"):
        require(item.attrib["version"] == baseline()["compiler_version"] and item.attrib["goal"] in ("compile", "testCompile"),
                "SG_BUILD_SCOPE", "实际编译器版本或目标未校准")
        purpose = "production" if item.attrib["goal"] == "compile" else "test"
        roots = [pathlib.Path(root.attrib["path"]).resolve() for root in item.findall("root")]
        require(all(root.is_relative_to(project) for root in roots), "SG_BUILD_SCOPE", "源码根越出隔离工程")
        sources = {str(path.resolve()) for root in roots for path in root.rglob("*.java")}
        if not sources:
            continue
        status = project / "target/maven-status/maven-compiler-plugin" / item.attrib["goal"] / item.attrib["execution"]
        observed = listed_files(status / "inputFiles.lst")
        exact_coverage(sources, set(observed), "SG_SOURCE_COVERAGE", "维护源码被排除或未实际编译")
        require(not assigned.intersection(sources), "SG_SOURCE_COVERAGE", "维护源码重复归属编译执行")
        output = pathlib.Path(item.attrib["output"]).resolve()
        require(output.is_relative_to(project / "target"), "SG_BUILD_SCOPE", "编译输出越出已校准范围")
        require(not any(path.is_symlink() for path in output.rglob("*")), "SG_BUILD_INPUT", "实际编译输出含符号链接")
        classes = listed_files(status / "createdFiles.lst", output)
        exact_coverage({str(path.resolve()) for path in output.rglob("*.class")}, set(classes),
                       "SG_BYTECODE_COVERAGE", "实际类产物与编译器清单不一致")
        assigned.update(sources)
        classpath = compilation_classpath(item, project, repository)
        compilations.append({"purpose": purpose, "goal": item.attrib["goal"], "execution": item.attrib["execution"],
                             "roots": list(map(str, roots)), "sources": observed, "output": str(output), "classes": classes,
                             "classpath": classpath, "dependencies": [dict(node.attrib) for node in item.findall("classpathDependency")]})
    require(compilations and any(item["purpose"] == "production" for item in compilations),
            "SG_SOURCE_COVERAGE", "没有实际生产编译范围，不能以空执行通过")
    exact_coverage({path for path in inputs if path.endswith(".java")}, assigned,
                   "SG_UNKNOWN_SOURCE", "工程存在未归属实际编译的维护源码")
    return compilations


def direct_dependencies(document):
    """直接身份来自原生依赖图根节点，BOM 或碰巧解析到的传递对象不算入口。"""
    project = document.find("project")
    resolutions = document.findall("dependencyResolution")
    require(project is not None and all(all(item.get(key) == project.get(key) for key in ("group", "artifact", "version"))
            for item in resolutions), "SG_DEPENDENCY_DECLARATION", "直接依赖图不属于实际项目")
    nodes = [dict(node.attrib) for resolution in resolutions for node in resolution.findall("constraint")]
    require(all(node.get("direct") in {"true", "false"} and node.get("scope") in {"compile", "provided", "runtime", "test", "system"}
            and all(isinstance(node.get(key), str) and node[key] for key in ("group", "artifact", "version", "extension"))
            and isinstance(node.get("classifier"), str) for node in nodes),
            "SG_DEPENDENCY_DECLARATION", "原生直接依赖观察缺少身份或用途")
    fields = ("group", "artifact", "version", "extension", "classifier", "scope")
    observations = {}
    for node in nodes:
        observations.setdefault(tuple(node[key] for key in fields), set()).add(node["direct"])
    require(all(len(values) == 1 for values in observations.values()),
            "SG_DEPENDENCY_DECLARATION", "同一原生依赖的直接身份矛盾")
    direct = {identity for identity, values in observations.items() if values == {"true"}}
    declared = {(node.get("group"), node.get("artifact"), node.get("scope")) for node in project.findall("declaredDependency")}
    exact_coverage(declared, {(item[0], item[1], item[5]) for item in direct},
                   "SG_DEPENDENCY_DECLARATION", "原生直接依赖与有效声明不一致")
    return [dict(zip(fields, item)) for item in sorted(direct)]


def probe(args):
    home, environment = runtime(args.maven_home)
    project, inputs = validate_project(pathlib.Path(args.project))
    repository = pathlib.Path(args.repository or "")
    require(args.repository and repository.is_dir() and not repository.resolve().is_relative_to(project) and
            not repository.is_symlink(), "SG_BUILD_INPUT", "必须提供工程外专用依赖目录")
    configuration = {name: digest(HERE / name) for name in ("maven-scope.lock.json", "MavenScopeSpy.java", "calibrate_maven.py", "calibrate_source.py")}
    with tempfile.TemporaryDirectory(prefix="domain-forge-maven-scope-") as temporary:
        document, argv = compile_project(home, project, repository.resolve(), pathlib.Path(temporary))
        compilations = compilation_inventory(document, project, inputs, repository)
        direct = direct_dependencies(document)
    require(inputs == project_inputs(project) and configuration == {name: digest(HERE / name) for name in configuration},
            "SG_INPUT_CHANGED", "执行期间维护输入或观察器改变")
    _, after = runtime(args.maven_home)
    require(environment == after, "SG_INPUT_CHANGED", "执行期间 Maven 或编译环境改变")
    artifacts = {path: digest(pathlib.Path(path)) for item in compilations for path in item["classes"]}
    classpath_artifacts = {path: digest(pathlib.Path(path)) for item in compilations for path in item["classpath"] if pathlib.Path(path).is_file()}
    return {"ok": True, "proof_scope": "observed-maven-compilation-scope-only", "semantic_purpose_proven": False,
            "publication_proven": False, "human_acceptance": False, "compilations": compilations,
            "inputs": inputs, "artifacts": artifacts, "classpath_artifacts": classpath_artifacts, "direct_dependencies": direct,
            "configuration": configuration, "environment": environment,
            "command": argv, "diagnostics": []}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--project")
    operation.add_argument("--environment", action="store_true")
    operation.add_argument("--prepare", metavar="EMPTY_DIRECTORY")
    parser.add_argument("--maven-home", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN"))
    parser.add_argument("--repository", default=os.environ.get("DOMAIN_FORGE_GATE_MAVEN_REPOSITORY"))
    args = parser.parse_args()
    try:
        if args.prepare:
            result = prepare(args.prepare)
        elif args.environment:
            result = environment(args)
        else:
            result = probe(args)
    except ProbeFailure as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        result = {"ok": False, "diagnostics": [{"code": "SG_BUILD_INPUT", "message": type(error).__name__}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
