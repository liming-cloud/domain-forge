"""将实际选中的生产方法合同绑定 JDK 源声明，只检查说明结构，不判断语义。"""

import os
import pathlib
import tempfile
import xml.etree.ElementTree as ET

from calibrate_source import HERE, checkstyle, execute, require


def inventory(build):
    """重新分析同次构建全部源码，逐项核对文件，不接受自报方法位置或文档。"""
    sources = [path for item in build["compilations"] for path in item["sources"]]
    roots = list(dict.fromkeys(path for item in build["compilations"] for path in item["classpath"]))
    with tempfile.TemporaryDirectory(prefix="domain-forge-method-inventory-") as temporary:
        compiled = execute(["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-d", temporary,
                            str(HERE / "MethodInventory.java")])
        require(compiled.returncode == 0, "SG_TOOL_FAILURE", "方法声明适配器实际编译失败：" + compiled.stderr)
        process = execute(["java", "-cp", temporary, "MethodInventory", os.pathsep.join(roots), *sources])
    require(process.returncode == 0 and not process.stderr, "SG_METHOD_BINDING", "实际源码语义分析未完成：" + process.stderr)
    output = ET.fromstring(process.stdout)
    files = output.findall("file")
    require(output.tag == "inventory" and len(files) == len(sources) and
            {file.attrib["name"] for file in files} == set(sources), "SG_METHOD_BINDING", "方法声明解析范围不等于实际编译源码")
    methods = [dict(item.attrib, file=file.attrib["name"],
                    overrides=[target.attrib["id"] for target in item.findall("overrides")])
               for file in files for item in file.findall("method")]
    require(len(methods) == len({item["id"] for item in methods}), "SG_METHOD_BINDING", "方法声明不能唯一关联")
    references = [dict(item.attrib, file=file.attrib["name"]) for file in files for item in file.findall("type_reference")]
    require(all(isinstance(item.get("owner"), str) and item.get("usage") in {"import", "annotation", "annotation-value", "type"}
                for item in references) and
            len(references) == len({(item["file"], item["owner"], item["target"], item["usage"]) for item in references}),
            "SG_DEPENDENCY_DECLARATION", "源码类型引用重复或无法唯一定位")
    sql_issues = [dict(item.attrib, file=file.attrib["name"])
                  for file in files for item in file.findall("sql_violation")]
    return methods, references, sql_issues


def own_comment_issue(method):
    """方法原有说明须有正文、可定位且在所有注解和修饰符之前。"""
    if method["doc_present"] != "true":
        return "SG_METHOD_JAVADOC"
    if (int(method["doc_start"]) < 0 or int(method["doc_end"]) < int(method["doc_start"])
            or int(method["doc_end"]) >= int(method["start"])):
        return "SG_METHOD_POSITION"
    if method["doc_body"] != "true" or method["doc_error"] == "true":
        return "SG_METHOD_JAVADOC"
    return None


def comment_sources(method, methods, visiting=frozenset()):
    """同次源码中存在唯一有效继承来源才建立机械关联；未知来源不能自动免除。"""
    if method["id"] in visiting or method["explicit"] != "true":
        return []
    issue = own_comment_issue(method)
    if method["doc_present"] == "true" and issue:
        return []
    if issue is None and method["doc_inherit"] != "true":
        return [method["id"]]
    inherited = set()
    for identifier in method["overrides"]:
        if identifier in methods:
            inherited.update(comment_sources(methods[identifier], methods, visiting | {method["id"]}))
    return sorted(inherited)


def bind(build, bindings, contracts, paths, *, sql_policy=False):
    """只选择精确合同方法与构造器，不强加内部访问器或同包测试的说明责任。"""
    observed, references, sql_issues = inventory(build)
    methods = {item["id"]: item for item in observed}
    owners = {item["name"]: item for item in bindings}
    purposes = {path: compilation["purpose"] for compilation in build["compilations"] for path in compilation["sources"]}
    positions = checkstyle(paths, [pathlib.Path(path) for path in purposes], "comments", "test")
    diagnostics = [dict(item, code="SG_METHOD_POSITION", purpose=purposes[item["file"]])
                   for item in positions if item["rule"] == "InvalidJavadocPosition"]
    if sql_policy:
        diagnostics.extend(dict(item, purpose=purposes[item["file"]]) for item in sql_issues)
    selected = []
    for contract in contracts:
        for member in contract["members"]:
            if "(" not in member["id"]:
                continue
            identifier = member["id"]
            method = methods.get(identifier)
            details = {"member": identifier, "file": contract["source"], "purpose": contract["purpose"]}
            generated = method is not None and method.get("generated_kind") in {
                "record-accessor", "enum-values", "enum-value-of"} and method["explicit"] == "false"
            if (method is None or (method["explicit"] != "true" and not generated) or method["file"] != contract["source"]
                    or method["owner"] != contract["name"]):
                diagnostics.append(dict(code="SG_METHOD_BINDING", **details))
                continue
            details["line"] = int(method["line"])
            if generated:
                # JDK语义树给出的精确编译器成员绑定类型说明，不伪造方法正文或位置。
                declaration = dict(method, start=method["declaration_start"])
                issue = own_comment_issue(declaration)
                if issue:
                    diagnostics.append(dict(code=issue, **details))
                selected.append(dict(method, module=owners[method["owner"]]["module"], purpose=contract["purpose"],
                                     comment_sources=[dict(type=method["owner"], file=method["file"], line=int(method["line"]))],
                                     semantic_review_proven=False))
                continue
            sources = comment_sources(method, methods)
            issue = own_comment_issue(method)
            if issue and method["doc_present"] == "true":
                diagnostics.append(dict(code=issue, **details))
            elif len(sources) != 1:
                diagnostics.append(dict(code="SG_METHOD_INHERITANCE" if method["overrides"] or method.get("doc_inherit") == "true"
                                        else "SG_METHOD_JAVADOC", **details))
            inherited = [dict(member=source, file=methods[source]["file"], line=int(methods[source]["line"]))
                         for source in sources if source != identifier]
            selected.append(dict(method, module=owners[method["owner"]]["module"], purpose=contract["purpose"],
                                 comment_sources=inherited, semantic_review_proven=False))
    return selected, diagnostics, references
