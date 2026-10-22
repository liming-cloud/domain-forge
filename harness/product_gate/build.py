"""将原生生命周期、源码和架构观察绑定到同一真实产品构建。"""

import copy
import json
from pathlib import Path
from types import SimpleNamespace
import xml.etree.ElementTree as ET
import zipfile

from environment import (AGGREGATES, CONSUMER_AGGREGATE, CONSUMERS, JAVA_MODULES, MODULE, REACTOR,
                         baseline, command, digest, require, sources, product_root)
import calibrate_architecture
import calibrate_lifecycle
import calibrate_maven
import calibrate_maven_architecture
import calibrate_maven_source
import calibrate_source
import dependency_declarations
import method_contracts
import role_boundaries
import structural_contracts


def model(root, project):
    aggregate = ET.parse(product_root(project) / "pom.xml").getroot()
    expected = {"modelVersion": "4.0.0", "groupId": "org.domainforge", "artifactId": "platform",
                "version": "0.1.0", "packaging": "pom"}
    require(sorted(x.tag.rsplit("}", 1)[-1] for x in aggregate
                   if x.tag.rsplit("}", 1)[-1] not in ("name", "description")) ==
            sorted([*expected, "modules", "properties", "dependencyManagement", "build"]) and
            all(len(aggregate.findall("{*}" + key)) <= 1 for key in ("name", "description")) and
            all(aggregate.findtext("{*}" + key) == value for key, value in expected.items()) and
            [x.text for x in aggregate.findall("./{*}modules/{*}module")] == list(AGGREGATES[Path(".")]) and
            len(aggregate.find("{*}modules")) == len(AGGREGATES[Path(".")]),
            "PG_BUILD_SCOPE", "聚合必须精确包含已登记的真实产品模块，不接受空目标、额外模块或隐式配置")
    parent_management(root, aggregate)
    compiler_model(aggregate, project)
    hierarchy(product_root(project))
    document = ET.parse(project / "pom.xml").getroot()
    require(not any(document.find(path) is not None for path in ("./{*}modules", "./{*}profiles",
                "./{*}build/{*}directory", "./{*}build/{*}outputDirectory", "./{*}build/{*}testOutputDirectory")),
            "PG_BUILD_SCOPE", "本单元仅支持默认输出的单工程")
    compiler_model(document, project)
    properties = project / "src/main/resources/application.properties"
    config = dict(line.split("=", 1) for line in properties.read_text().splitlines() if line and not line.startswith("#"))
    require(config.get("server.address") == "127.0.0.1", "PG_RUNTIME_TARGET", "默认监听须保持回环")
    require(not any(p.suffix in {".properties", ".yml", ".yaml"} and p != properties
                    for p in (project / "src/main/resources").rglob("*")),
            "PG_BUILD_SCOPE", "额外运行配置来源须先定义并验证覆盖合同")


def hierarchy(code):
    """核对逐层继承和直接子模块；独立消费聚合不改变 A/B 的父 POM 合同。"""
    paths = {Path("."), *REACTOR, CONSUMER_AGGREGATE, *CONSUMERS, *JAVA_MODULES}
    for relative in sorted(paths):
        directory = code / relative
        document = ET.parse(directory / "pom.xml").getroot()
        require(document.findtext("{*}artifactId") == directory.name,
                "PG_MODULE_NAME", "模块目录名必须与 artifactId 一致：" + str(relative))
        if relative in REACTOR:
            parent = document.find("{*}parent")
            expected = {"groupId": "org.domainforge", "artifactId": directory.parent.name,
                        "version": "0.1.0", "relativePath": "../pom.xml"}
            require(parent is not None and len(parent) == len(expected) and
                    all(parent.findtext("{*}" + key) == value for key, value in expected.items()),
                    "PG_BASELINE", "内部模块须直接继承所在层级 parent：" + str(relative))
        if relative in AGGREGATES and relative != Path("."):
            require(sorted(x.tag.rsplit("}", 1)[-1] for x in document
                           if x.tag.rsplit("}", 1)[-1] not in ("name", "description")) ==
                    sorted(["modelVersion", "parent", "artifactId", "packaging", "modules"]) and
                    all(len(document.findall("{*}" + key)) <= 1 for key in ("name", "description")) and
                    document.findtext("{*}modelVersion") == "4.0.0" and
                    document.findtext("{*}packaging") == "pom" and
                    [x.text for x in document.findall("./{*}modules/{*}module")] == list(AGGREGATES[relative]) and
                    len(document.find("{*}modules")) == len(AGGREGATES[relative]),
                    "PG_BUILD_SCOPE", "中间聚合只管理直接子模块，不注入依赖、属性或执行：" + str(relative))
    consumer = ET.parse(code / CONSUMER_AGGREGATE / "pom.xml").getroot()
    expected = {"modelVersion": "4.0.0", "groupId": "org.domainforge.verification",
                "artifactId": "consumers", "version": "0.1.0", "packaging": "pom"}
    require(sorted(x.tag.rsplit("}", 1)[-1] for x in consumer) == sorted([*expected, "modules"]) and
            all(consumer.findtext("{*}" + key) == value for key, value in expected.items()) and
            [x.text for x in consumer.findall("./{*}modules/{*}module")] == [p.name for p in CONSUMERS] and
            len(consumer.find("{*}modules")) == len(CONSUMERS),
            "PG_CONSUMER_SCOPE", "消费聚合只管理 A/B，不继承平台或注入配置")


def parent_management(root, document):
    """内部 parent 仅提供已支持的管理项；不混入依赖或聚合插件执行。"""
    lock = baseline(root)
    bom = document.findall("./{*}dependencyManagement/{*}dependencies/{*}dependency")
    expected = [("org.springframework.boot", "spring-boot-dependencies", "spring-boot.version", lock["boot_version"]),
                ("org.springframework.cloud", "spring-cloud-dependencies", "spring-cloud.version", lock["cloud_version"]),
                ("com.alibaba.cloud", "spring-cloud-alibaba-dependencies", "spring-cloud-alibaba.version", lock["alibaba_version"])]
    require(len(bom) == len(expected) and all(
            len(item) == 5 and item.findtext("{*}groupId") == group and
            item.findtext("{*}artifactId") == artifact and item.findtext("{*}version") == "${" + prop + "}" and
            item.findtext("{*}type") == "pom" and item.findtext("{*}scope") == "import" and
            document.findtext("./{*}properties/{*}" + prop) == version
            for item, (group, artifact, prop, version) in zip(bom, expected)),
            "PG_BASELINE", "内部 parent 须按固定顺序导入 Boot／Cloud／Alibaba BOM，不得混用版本或额外覆盖")
    require([x.tag.rsplit("}", 1)[-1] for x in document.find("{*}build")] == ["pluginManagement"] and
            [x.tag.rsplit("}", 1)[-1] for x in document.find("./{*}build/{*}pluginManagement")] == ["plugins"],
            "PG_BUILD_SCOPE", "内部 parent 只管理插件，不启用聚合执行或覆盖输出")
    plugins = document.findall("./{*}build/{*}pluginManagement/{*}plugins/{*}plugin")
    versions = {("org.apache.maven.plugins", "maven-" + name + "-plugin"): version
                for name, version in lock["plugins"].items()}
    versions[("org.springframework.boot", "spring-boot-maven-plugin")] = "${spring-boot.version}"
    versions[("org.codehaus.mojo", "flatten-maven-plugin")] = json.loads((root / "harness/product_gate/web-baseline.json").read_text())["tools"]["flatten"]["version"]
    actual = {(x.findtext("{*}groupId"), x.findtext("{*}artifactId")): x.findtext("{*}version") for x in plugins}
    require(len(plugins) == len(versions) and actual == versions, "PG_BASELINE", "内部插件管理偏离固定版本或覆盖范围")
    require(all({x.tag.rsplit("}", 1)[-1] for x in plugin} <=
                {"groupId", "artifactId", "version", "configuration"} for plugin in plugins),
            "PG_BUILD_SCOPE", "内部 parent 不隐式注入插件执行、扩展或插件依赖")


def compiler_model(document, project):
    """分别检查两层配置；管理中的编译配置也复用既有类路径与 fork 守卫。"""
    document = copy.deepcopy(document)
    for name in ("parent", "modules"):
        node = document.find("{*}" + name)
        if node is not None:
            document.remove(node)
    managed = document.findall("./{*}build/{*}pluginManagement/{*}plugins/{*}plugin")
    if managed:
        build = document.find("{*}build")
        active = build.find("{*}plugins")
        if active is None:
            active = ET.SubElement(build, "plugins")
        active.extend(managed)
    temporary = project / "model-check"
    temporary.mkdir()
    ET.ElementTree(document).write(temporary / "pom.xml")
    try:
        calibrate_maven.validate_model(temporary)
    finally:
        (temporary / "pom.xml").unlink()
        temporary.rmdir()


def lifecycle(root, project, argv, env, home, repository, output):
    require(not (project / "target").exists(), "PG_DIRTY_BUILD", "产品交付从无旧输出副本执行")
    source_inputs = sources(product_root(project).parent)
    model(root, project)
    inputs = calibrate_maven.project_inputs(project)
    extension = calibrate_maven.observer(home, output)
    inventory = output / "maven-inventory.xml"
    execution = command([*argv, "-f", str(product_root(project) / "pom.xml"), "-Dmaven.ext.class.path=" + str(extension),
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
    require(sources(product_root(project).parent) == source_inputs, "PG_INPUT_CHANGED", "聚合或模块输入在构建期间变化")
    return {"build": build, "execution": execution, "goals": goals, "tests": tests, "artifact": artifact,
            "dependencies": dependencies, "plugin_dependencies": plugin_dependencies,
            "constraints": constraints, "plugin_constraints": plugin_constraints, "resolved": resolved,
            "inventory": {"path": str(inventory), "sha256": digest(inventory)},
            "module_inventory": {"path": str(module_path), "sha256": digest(module_path)},
            "reactor": [".", *[item.as_posix() for item in REACTOR]]}


def module_inventory(document, project):
    """核对全部真实 reactor，再选择当前 JAR 的原生观察，不丢弃未知执行。"""
    code = product_root(project)
    projects = document.findall("project")
    expected = {str(code), *(str(code / item) for item in REACTOR)}
    require(len(projects) == len(expected) and {x.get("path") for x in projects} == expected,
            "PG_BUILD_SCOPE", "实际 reactor 遗漏或增加了未经覆盖的模块")
    for relative in AGGREGATES:
        aggregate = next(x for x in projects if x.get("path") == str(code / relative))
        require(aggregate.get("packaging") == "pom" and aggregate.get("artifact") == (code / relative).name and
                not any(aggregate.findall(tag) for tag in ("declaredDependency", "resolvedDependency")),
                "PG_BUILD_SCOPE", "内部聚合不能包含未验证的产品依赖")
    allowed = {str(code / item) for item in REACTOR if item not in AGGREGATES}
    require(all(x.get("project") in allowed for tag in ("compilation", "execution") for x in document.findall(tag)),
            "PG_BUILD_SCOPE", "聚合或未知模块执行了未支持的编译／插件")
    bom = next(x for x in projects if x.get("path") == str(code / "domain-forge-bom"))
    require(bom.get("packaging") == "pom" and not any(bom.findall(tag) for tag in ("declaredDependency", "resolvedDependency"))
            and not any(x.get("project") == str(code / "domain-forge-bom") for x in document.findall("compilation"))
            and all(x.get("group") == "org.codehaus.mojo" and x.get("artifact") == "flatten-maven-plugin" and
                    x.get("goal") == "flatten" for x in document.findall("execution") if x.get("project") == str(code / "domain-forge-bom")),
            "PG_BUILD_SCOPE", "平台 BOM 只能生成消费 POM，不能包含直接依赖、编译或额外执行")
    return select_inventory(document, project)


def select_inventory(document, project):
    """按精确路径和坐标选择单模块；其他模块由入口全量覆盖检查单独验证。"""
    declarations = [node for model in document.findall("project") for tag in
                    ("declaredDependency", "declaredPlugin", "declaredPluginDependency") for node in model.findall(tag)]
    require(all(calibrate_lifecycle.fixed_version(node.get("version")) for node in [*document.findall("project"), *declarations]),
            "SG_BUILD_VERSION", "全 reactor 有效项目、依赖或插件声明包含浮动版本")
    fields = ("group", "artifact", "version", "extension", "classifier")
    identity_of = lambda item: tuple(item.get(key) for key in fields)
    constraints = document.findall("pluginConstraint")
    require({identity_of(x) for x in constraints} == {identity_of(x) for x in document.findall("execution/pluginDependency")},
            "SG_BUILD_RESOLUTION", "全 reactor 插件约束与执行对象不一致")
    require(all(calibrate_lifecycle.fixed_version(x.get("constraint")) for x in constraints),
            "SG_BUILD_VERSION", "全 reactor 插件约束包含浮动版本")
    result = copy.deepcopy(document)
    selected = [x for x in result.findall("project") if x.get("path") == str(project)]
    require(len(selected) == 1, "PG_BUILD_SCOPE", "模块原生观察缺失或重复")
    identity = selected[0].attrib
    for node in list(result):
        if node.tag == "project" and node.get("path") != str(project):
            result.remove(node)
        elif node.tag in ("compilation", "execution") and node.get("project") != str(project):
            result.remove(node)
        elif node.tag == "dependencyResolution" and any(node.get(key) != identity.get(key) for key in ("group", "artifact", "version")):
            result.remove(node)
    used = {identity_of(x) for x in result.findall("execution/pluginDependency")}
    for node in list(result.findall("pluginConstraint")):
        if identity_of(node) not in used:
            result.remove(node)
    return result


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


def quality(root, project, observed, tools, declaration="modules.json", contract_sha256=None):
    """直接使用观察器的真实对象执行必要源码、依赖、合同与职责检查。"""
    build = observed["build"]
    paths, tool_environment = calibrate_source.toolchain(tools)
    groups, implementations, diagnostics = calibrate_maven_source.source_checks(SimpleNamespace(tools=tools), build, paths)
    definition = root / "harness/product_gate" / declaration
    expected_contract = contract_sha256 or baseline(root).get("module_contracts", {}).get(declaration)
    structural_contracts.bind_declaration(definition, expected_contract)
    modules = calibrate_architecture.declaration(definition, schema=4)
    roots, inputs, expected = calibrate_architecture.bytecode_inputs([item["output"] for item in build["compilations"]])
    require(inputs == build["artifacts"], "PG_SOURCE_BINDING", "导入类产物不属于同次构建")
    types = calibrate_architecture.inventory(paths, roots, expected, list(build["classpath_artifacts"]))
    bindings = calibrate_maven_architecture.bind_types(build, types, paths, modules)
    diagnostics.extend(calibrate_architecture.violations(structural_contracts.owned(modules), types))
    diagnostics.extend(calibrate_maven_architecture.origin_violations(build, types))
    contracts = calibrate_maven_architecture.select_contracts(modules, types, bindings)
    methods, errors, references = method_contracts.bind(build, bindings, contracts, paths, sql_policy=True)
    diagnostics.extend(errors)
    declarations, errors = dependency_declarations.inspect(build, references, [])
    diagnostics.extend(errors)
    report = {"build": build, "bindings": bindings, "inventory": types, "dependency_declarations": declarations,
              "method_contracts": methods}
    report["component_inventory"] = structural_contracts.external_inventory(report, modules, paths)
    roles, errors = role_boundaries.inspect(report, modules, role_boundaries.baseline())
    diagnostics.extend(errors)
    calibrate_maven_source.unchanged_build(build, project)
    structural_contracts.bind_declaration(definition, expected_contract)
    return {"ok": not diagnostics, "compilation_checks": groups, "implementations": implementations,
            "bindings": bindings, "contracts": contracts, "methods": methods, "roles": roles,
            "dependency_declarations": declarations, "tool_environment": tool_environment, "diagnostics": diagnostics}
