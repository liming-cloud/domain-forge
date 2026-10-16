"""将原生生命周期、源码和架构观察绑定到同一真实产品构建。"""

import json
from pathlib import Path
from types import SimpleNamespace
import xml.etree.ElementTree as ET
import zipfile

from environment import MODULE, baseline, command, digest, require, sources
import calibrate_architecture
import calibrate_lifecycle
import calibrate_maven
import calibrate_maven_architecture
import calibrate_maven_source
import calibrate_source
import dependency_declarations
import method_contracts
import role_boundaries


def model(root, project):
    aggregate = ET.parse(project.parents[1] / "pom.xml").getroot()
    expected = {"modelVersion": "4.0.0", "groupId": "org.domainforge", "artifactId": "domain-forge-build",
                "version": "0.1.0", "packaging": "pom"}
    require(sorted(x.tag.rsplit("}", 1)[-1] for x in aggregate) == sorted([*expected, "modules"]) and
            all(aggregate.findtext("{*}" + key) == value for key, value in expected.items()) and
            [x.text for x in aggregate.findall("./{*}modules/{*}module")] == [MODULE.as_posix()] and
            len(aggregate.find("{*}modules")) == 1,
            "PG_BUILD_SCOPE", "聚合必须只包含已支持的真实探针，不接受空目标、额外模块或隐式配置")
    document = ET.parse(project / "pom.xml")
    parent = document.find("./{*}parent")
    require(parent is not None and parent.findtext("{*}groupId") == "org.springframework.boot" and
            parent.findtext("{*}artifactId") == "spring-boot-starter-parent" and
            parent.findtext("{*}version") == baseline(root)["boot_version"], "PG_BASELINE", "工程父版本偏离已验证 Boot 基线")
    require(not any(document.find(path) is not None for path in ("./{*}modules", "./{*}profiles",
                "./{*}build/{*}directory", "./{*}build/{*}outputDirectory", "./{*}build/{*}testOutputDirectory")),
            "PG_BUILD_SCOPE", "本单元仅支持默认输出的单工程")
    # 复用对编译器、类路径和隐式 fork 参数的守卫；已验证的父 POM 单独核对。
    document.getroot().remove(parent)
    temporary = project / "model-check"
    temporary.mkdir()
    document.write(temporary / "pom.xml")
    try:
        calibrate_maven.validate_model(temporary)
    finally:
        (temporary / "pom.xml").unlink()
        temporary.rmdir()
    properties = project / "src/main/resources/application.properties"
    config = dict(line.split("=", 1) for line in properties.read_text().splitlines() if line and not line.startswith("#"))
    require(config.get("server.address") == "127.0.0.1", "PG_RUNTIME_TARGET", "默认监听须保持回环")
    require(not any(p.suffix in {".properties", ".yml", ".yaml"} and p != properties
                    for p in (project / "src/main/resources").rglob("*")),
            "PG_BUILD_SCOPE", "额外运行配置来源须先定义并验证覆盖合同")


def lifecycle(root, project, argv, env, home, repository, output):
    require(not (project / "target").exists(), "PG_DIRTY_BUILD", "产品交付从无旧输出副本执行")
    source_inputs = sources(project.parents[2])
    model(root, project)
    inputs = calibrate_maven.project_inputs(project)
    extension = calibrate_maven.observer(home, output)
    inventory = output / "maven-inventory.xml"
    execution = command([*argv, "-f", str(project.parents[1] / "pom.xml"), "-Dmaven.ext.class.path=" + str(extension),
                         "-Ddomainforge.scope.output=" + str(inventory), "verify"],
                        project, output / "verify.log", 600, env)
    require(inventory.is_file(), "PG_BUILD_OBSERVER", "缺少本次真实 Maven 观察结果")
    document = ET.parse(inventory).getroot()
    document = module_inventory(document, project)
    module_path = output / "module-inventory.xml"
    ET.ElementTree(document).write(module_path)
    lock = baseline(root)
    require(Path(document.attrib["mavenHome"]).resolve() == home and
            document.attrib["javaVersion"] == lock["java"]["runtime_version"].split("+")[0],
            "PG_ENVIRONMENT", "实际构建 JDK/Maven 与 Wrapper 预检不一致")
    require([x.get("goal") for x in document.findall("requested")] == ["verify"],
            "PG_BUILD_LIFECYCLE", "本次请求未完整运行 verify")
    goals = [dict(x.attrib) for x in document.findall("execution")]
    required = [("resources", "resources", "process-resources"), ("resources", "testResources", "process-test-resources"),
                ("compiler", "compile", "compile"), ("compiler", "testCompile", "test-compile"),
                ("surefire", "test", "test"), ("jar", "jar", "package")]
    for plugin, goal, phase in required:
        require(any(x["group"] == "org.apache.maven.plugins" and x["artifact"] == "maven-" + plugin + "-plugin"
                    and x["version"] == lock["plugins"][plugin] and x["goal"] == goal and x["phase"] == phase
                    for x in goals), "PG_BUILD_LIFECYCLE", "缺失或偏离必要目标：" + plugin + ":" + goal)
    require(any(x["group"] == "org.springframework.boot" and x["artifact"] == "spring-boot-maven-plugin" and
                x["goal"] == "repackage" and x["version"] == lock["boot_version"] for x in goals),
            "PG_BUILD_ARTIFACT", "没有实际 Boot 打包执行")
    compilations = calibrate_maven.compilation_inventory(document, project, inputs, repository)
    calibrate_lifecycle.compilation_target(compilations)
    dependencies, plugin_dependencies, constraints, plugin_constraints, resolved = calibrate_lifecycle.versions_and_artifacts(document, repository)
    tests = calibrate_lifecycle.test_results(project, compilations, set(lock["required_tests"]))
    build = {"ok": True, "inputs": inputs, "compilations": compilations,
             "artifacts": {name: digest(Path(name)) for item in compilations for name in item["classes"]},
             "classpath_artifacts": {name: digest(Path(name)) for item in compilations for name in item["classpath"] if Path(name).is_file()},
             "direct_dependencies": calibrate_maven.direct_dependencies(document)}
    artifact = packaged(document, project, build, dependencies)
    calibrate_maven_source.unchanged_build(build, project)
    require(sources(project.parents[2]) == source_inputs, "PG_INPUT_CHANGED", "聚合或模块输入在构建期间变化")
    return {"build": build, "execution": execution, "goals": goals, "tests": tests, "artifact": artifact,
            "dependencies": dependencies, "plugin_dependencies": plugin_dependencies,
            "constraints": constraints, "plugin_constraints": plugin_constraints, "resolved": resolved,
            "inventory": {"path": str(inventory), "sha256": digest(inventory)},
            "module_inventory": {"path": str(module_path), "sha256": digest(module_path)},
            "reactor": [".", MODULE.as_posix()]}


def module_inventory(document, project):
    """先核对原生 reactor 全量覆盖，再复用单 JAR 的既有严格检查。"""
    projects = document.findall("project")
    code = project.parents[1]
    require(len(projects) == 2 and {x.get("path") for x in projects} == {str(code), str(project)},
            "PG_BUILD_SCOPE", "实际 reactor 遗漏或增加了未经覆盖的模块")
    aggregate = next(x for x in projects if x.get("path") == str(code))
    require(aggregate.get("packaging") == "pom" and aggregate.get("artifact") == "domain-forge-build" and
            not any(aggregate.findall(tag) for tag in ("declaredDependency", "resolvedDependency")),
            "PG_BUILD_SCOPE", "内部聚合不能包含未验证的产品依赖")
    require(all(x.get("project") == str(project) for tag in ("compilation", "execution") for x in document.findall(tag)),
            "PG_BUILD_SCOPE", "聚合或未知模块执行了未支持的编译／插件")
    document.remove(aggregate)
    for node in list(document.findall("dependencyResolution")):
        if all(node.get(key) == aggregate.get(key) for key in ("group", "artifact", "version")):
            require(len(node) == 0, "PG_BUILD_SCOPE", "聚合出现未覆盖的原生依赖图")
            document.remove(node)
    return document


def packaged(document, project, build, dependencies):
    """Boot 真实 JAR 的应用类、资源及库必须对应同次编译和最终解析。"""
    artifact = Path(document.find("project").attrib["artifactFile"])
    require(artifact.is_file() and not artifact.is_symlink() and artifact.resolve().is_relative_to(project / "target"),
            "PG_BUILD_ARTIFACT", "本次主产物缺失或越界")
    production = [item for item in build["compilations"] if item["purpose"] == "production"]
    classes = {"BOOT-INF/classes/" + Path(name).relative_to(item["output"]).as_posix(): Path(name)
               for item in production for name in item["classes"]}
    resources = {"BOOT-INF/classes/" + path.relative_to(project / "src/main/resources").as_posix(): path
                 for path in (project / "src/main/resources").rglob("*") if path.is_file()}
    jars, metadata_starters = {}, []
    for node in dependencies:
        if node["scope"] not in {"compile", "runtime"} or node["extension"] != "jar":
            continue
        path = Path(node["file"])
        with zipfile.ZipFile(path) as library:
            manifest = library.read("META-INF/MANIFEST.MF").decode() if "META-INF/MANIFEST.MF" in library.namelist() else ""
            metadata_only = "Spring-Boot-Jar-Type: dependencies-starter" in manifest
            if metadata_only:
                require({name for name in library.namelist() if not name.endswith("/")} <=
                        {"META-INF/MANIFEST.MF", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt"},
                        "PG_BUILD_ARTIFACT", "被排除的 starter 含运行内容")
                metadata_starters.append(dict(node))
            else:
                jars["BOOT-INF/lib/" + path.name] = node
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "PG_BUILD_ARTIFACT", "主产物包含重复条目")
        actual = {name for name in names if name.startswith("BOOT-INF/classes/") and not name.endswith("/")}
        require(actual == set(classes) | set(resources), "PG_BUILD_ARTIFACT", "应用类或资源覆盖不一致")
        require(all(archive.read(name) == path.read_bytes() for name, path in (classes | resources).items()),
                "PG_BUILD_ARTIFACT", "应用类或资源字节偏离输入")
        actual_jars = {name for name in names if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")}
        require(set(jars) == actual_jars and all(archive.read(name) == Path(node["file"]).read_bytes() for name, node in jars.items()),
                "PG_BUILD_ARTIFACT", "打包依赖与最终解析字节不一致")
    return {"path": str(artifact), "sha256": digest(artifact), "classes": sorted(classes),
            "resources": sorted(resources), "dependencies": sorted(jars), "metadata_starters": metadata_starters}


def quality(root, project, observed, tools):
    """直接使用观察器的真实对象执行必要源码、依赖、合同与职责检查。"""
    build = observed["build"]
    paths, tool_environment = calibrate_source.toolchain(tools)
    groups, implementations, diagnostics = calibrate_maven_source.source_checks(SimpleNamespace(tools=tools), build, paths)
    modules = calibrate_architecture.declaration(root / "tools/product_gate/modules.json", schema=3)
    roots, inputs, expected = calibrate_architecture.bytecode_inputs([item["output"] for item in build["compilations"]])
    require(inputs == build["artifacts"], "PG_SOURCE_BINDING", "导入类产物不属于同次构建")
    types = calibrate_architecture.inventory(paths, roots, expected, list(build["classpath_artifacts"]))
    bindings = calibrate_maven_architecture.bind_types(build, types, paths, modules)
    diagnostics.extend(calibrate_architecture.violations(modules, types))
    diagnostics.extend(calibrate_maven_architecture.origin_violations(build, types))
    contracts = calibrate_maven_architecture.select_contracts(modules, types, bindings)
    methods, errors, references = method_contracts.bind(build, bindings, contracts, paths)
    diagnostics.extend(errors)
    declarations, errors = dependency_declarations.inspect(build, references, [])
    diagnostics.extend(errors)
    report = {"build": build, "bindings": bindings, "inventory": types, "dependency_declarations": declarations}
    roles, errors = role_boundaries.inspect(report, modules, role_boundaries.baseline())
    diagnostics.extend(errors)
    calibrate_maven_source.unchanged_build(build, project)
    return {"ok": not diagnostics, "compilation_checks": groups, "implementations": implementations,
            "bindings": bindings, "contracts": contracts, "methods": methods, "roles": roles,
            "dependency_declarations": declarations, "tool_environment": tool_environment, "diagnostics": diagnostics}
