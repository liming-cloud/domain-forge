"""唯一配置驱动的文档索引、规则路由及执行证据入口；不生成任何人工批准。"""

import argparse
import fnmatch
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.parse


MARKER = "<!-- control-plane:generated; edit catalog/task facts, not this view -->"
CATALOG = "tools/control_plane/catalog.json"
ENTRY = "tools/control_plane/control_plane.py"


class Rejected(Exception):
    """携带稳定诊断码的可判定拒绝；不能把异常或缺口转换为通过。"""

    def __init__(self, code, message):
        self.diagnostic = {"code": code, "message": message}
        super().__init__(message)


def require(condition, code, message):
    """不满足合同前提时立即拒绝，保留可独立断言的原因。"""
    if not condition:
        raise Rejected(code, message)


def safe_path(root, relative):
    """只接受仓库内的普通相对路径，拒绝遍历、绝对路径及符号链接越界。"""
    path = pathlib.PurePosixPath(relative)
    require(relative and not path.is_absolute() and ".." not in path.parts and "\\" not in relative and
            path.as_posix() == relative and path.parts[0] != ".git",
            "CP_UNSAFE_PATH", f"不是仓库内相对路径：{relative}")
    target = root / relative
    require(target.resolve().is_relative_to(root.resolve()), "CP_UNSAFE_PATH", f"路径越界：{relative}")
    require(not any((root / pathlib.Path(*path.parts[:i])).is_symlink() for i in range(1, len(path.parts) + 1)),
            "CP_UNSAFE_PATH", f"输入或输出路径含符号链接：{relative}")
    return target


def read_text(path):
    """按 UTF-8 读取有效输入，失败时不产生默认空内容。"""
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise Rejected("CP_INPUT_UNREADABLE", str(path)) from error


def parse_json(text, location):
    """拒绝重复键及非法 JSON，避免后一个字段悄悄覆盖原有事实。"""
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "CP_DUPLICATE_KEY", f"重复键：{location}: {key}")
            result[key] = value
        return result

    try:
        return json.loads(text, object_pairs_hook=unique)
    except json.JSONDecodeError as error:
        raise Rejected("CP_INVALID_JSON", f"{location}: {error.msg}") from error


def markdown(text):
    """解析当前控制面采用的 ATX 标题与围栏代码，按真实重复标题生成锚点。"""
    headings, lines, counts = [], [], {}
    fence = None
    for number, line in enumerate(text.splitlines(), 1):
        boundary = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if boundary:
            token = boundary.group(1)
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = None
            continue
        if fence:
            continue
        lines.append(line)
        match = re.match(r"^(#{1,6})\s+(.+?)(?:\s+#+)?$", line)
        if match:
            title = match.group(2)
            plain = re.sub(r"[*`<>]", "", title).lower()
            slug = "".join(char for char in plain if char.isalnum() or char in "_ -").replace(" ", "-")
            count = counts.get(slug, 0)
            counts[slug] = count + 1
            headings.append({"level": len(match.group(1)), "title": title,
                             "anchor": slug + (f"-{count}" if count else ""), "line": number})
    return headings, "\n".join(lines)


def git(root, *args):
    """只读取指定仓库的 Git 事实；失败不回退为无变更。"""
    try:
        result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise Rejected("CP_GIT_INPUT", "Git 读取环境缺失或超时") from error
    require(result.returncode == 0, "CP_GIT_INPUT", f"Git 输入不可核对：{' '.join(args[:2])}")
    return result.stdout.decode("utf-8")


def changed_paths(root, base):
    """同时包含起点后的已提交、暂存、工作区及非忽略未跟踪文件；不折叠重命名前路径。"""
    require(re.fullmatch(r"[0-9a-f]{40,64}", base or ""), "CP_GIT_INPUT", "任务起点须为精确 Git SHA")
    require(git(root, "cat-file", "-t", base).strip() == "commit", "CP_GIT_INPUT", "任务起点不是提交")
    changed = git(root, "diff", "--name-only", "--no-renames", "-z", base, "--").split("\0")
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z").split("\0")
    return sorted(set(changed + untracked) - {""})


def input_snapshot(root):
    """固定所有受版本控制或非忽略输入的原始字节；报告须位于仓库外避免自引用。"""
    tracked = git(root, "ls-files", "--cached", "-z").split("\0")
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z").split("\0")
    inputs = {}
    for relative in sorted(set(tracked + untracked) - {""}):
        path = safe_path(root, relative)
        inputs[relative] = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else "deleted"
    serialized = json.dumps(inputs, sort_keys=True, ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(serialized).hexdigest(), inputs


class ControlPlane:
    """隐藏登记、引用、派生视图及真实差异的细节；公共边界是统一命令。"""

    def __init__(self, root):
        self.root = root.resolve()
        self.catalog = parse_json(read_text(safe_path(self.root, CATALOG)), CATALOG)
        require(self.catalog.get("schema") == 1, "CP_SCHEMA", "未知配置版本")
        for key in ("categories", "documents", "scopes", "task_types", "checks", "tasks"):
            require(isinstance(self.catalog.get(key), dict) and self.catalog[key], "CP_SCHEMA", f"缺少字典：{key}")
        self.documents = self.catalog["documents"]
        self.texts, self.headings, self.tasks = {}, {}, {}
        seen = set()
        for identifier, doc in self.documents.items():
            require(re.fullmatch(r"[A-Z][A-Z0-9-]*", identifier), "CP_SCHEMA", f"无效文档 ID：{identifier}")
            relative = doc["path"]
            require(relative not in seen, "CP_DOCUMENT_ID", f"同一正文重复登记：{relative}")
            seen.add(relative)
            require(doc["category"] in self.catalog["categories"], "CP_CATEGORY", f"未知分类：{identifier}")
            require(doc["kind"] in ("rules", "entry", "design", "task", "evidence"), "CP_SCHEMA", f"未知内容类型：{identifier}")
            self.texts[identifier] = read_text(safe_path(self.root, relative))
            self.headings[identifier] = markdown(self.texts[identifier])[0]
        for category in self.catalog["categories"].values():
            safe_path(self.root, category["index"])
            require(category["index"] not in seen and category["index"] != "docs/index.md",
                    "CP_CATEGORY", "派生索引不能覆盖正文或总索引")
            if category.get("archive"):
                safe_path(self.root, category["archive"])
        self.validate_routes()
        self.validate_maintenance()
        self.validate_reference_inputs()
        self.read_tasks()

    def reference(self, ref, historical=False):
        """校验稳定文档 ID 和真实小节，不接受整份文档或不存在的替代正文。"""
        identifier, marker, anchor = ref.partition("#")
        require(marker and identifier in self.documents, "CP_INVALID_REFERENCE", f"无效正文引用：{ref}")
        require(anchor in {item["anchor"] for item in self.headings[identifier]},
                "CP_INVALID_REFERENCE", f"不存在的小节：{ref}")
        require(historical or not self.documents[identifier].get("replaced_by"), "CP_REPLACED_REFERENCE", f"引用已被替代：{ref}")
        return self.documents[identifier]["path"] + "#" + anchor

    def scopes(self, scopes):
        """范围必须明确登记，未知输入不被忽略。"""
        require(isinstance(scopes, list), "CP_SCHEMA", "范围须为列表")
        for scope in scopes:
            require(scope in self.catalog["scopes"], "CP_UNKNOWN_SCOPE", f"未知范围：{scope}")

    def validate_reference_inputs(self):
        """登记的规则 JSON 引用也核对唯一正文小节，不只检查 Markdown 链接。"""
        inputs = self.catalog.get("reference_inputs", {})
        require(isinstance(inputs, dict), "CP_SCHEMA", "引用输入须为路径到字段列表的映射")
        for relative, fields in inputs.items():
            path = safe_path(self.root, relative)
            require(isinstance(fields, list) and fields and all(isinstance(field, str) and field for field in fields) and
                    len(set(fields)) == len(fields), "CP_SCHEMA", "引用字段须唯一且非空")
            content = parse_json(read_text(path), relative)
            require(isinstance(content, dict), "CP_SCHEMA", "引用输入须为 JSON 对象")
            for field in fields:
                require(isinstance(content.get(field), str), "CP_SCHEMA", f"引用字段缺失或不是字符串：{relative}: {field}")
                self.reference(content[field])

    def validate_routes(self):
        """检查登记结构、替代链、触发和验证责任；不推断规则全文的语义。"""
        for identifier, doc in self.documents.items():
            replacements = {identifier}
            while doc.get("replaced_by"):
                following = doc["replaced_by"]
                require(following in self.documents and following not in replacements,
                        "CP_REPLACEMENT", f"无效或循环替代关系：{identifier}")
                replacements.add(following)
                doc = self.documents[following]
        route_ids = set()
        for route in self.catalog["routes"]:
            require(route["id"] not in route_ids, "CP_DUPLICATE_ROUTE", route["id"])
            route_ids.add(route["id"])
            self.scopes(route["scopes"])
            require(route["scopes"] and route["refs"], "CP_MISSING_ROUTE", route["id"])
            require(route["checks"], "CP_MISSING_VERIFICATION", route["id"])
            for ref in route["refs"]:
                self.reference(ref)
            for check in route["checks"]:
                require(check in self.catalog["checks"], "CP_MISSING_VERIFICATION", f"未知检查：{check}")
        for scopes in self.catalog["task_types"].values():
            self.scopes(scopes)
        for classifier in self.catalog["classifiers"]:
            self.scopes(classifier["scopes"])
            require(classifier["scopes"] and classifier.get("purpose") in
                    ("documentation", "governance", "production", "test"), "CP_SCHEMA", "分类器用途未知或缺少范围")
        for scope in self.catalog["scopes"]:
            require(any(scope in route["scopes"] for route in self.catalog["routes"]),
                    "CP_MISSING_ROUTE", f"已登记范围没有路由：{scope}")
        for check, definition in self.catalog["checks"].items():
            stage = definition.get("stage", "current")
            require(stage in ("current", "product"), "CP_STAGE_POLICY", f"未知验证阶段：{check}")
            mechanisms = definition.get("mechanism_checks", [])
            if stage == "product":
                require(isinstance(definition.get("reason"), str) and definition["reason"].strip() and
                        isinstance(mechanisms, list) and mechanisms and
                        all(isinstance(item, str) and item in self.catalog["checks"] and
                            self.catalog["checks"][item].get("stage", "current") == "current" for item in mechanisms) and
                        len(mechanisms) == len(set(mechanisms)), "CP_STAGE_POLICY",
                        f"产品结果须关联唯一、当前必需的机制责任及后续理由：{check}")
            else:
                require("mechanism_checks" not in definition, "CP_STAGE_POLICY", f"当前责任不能设置延期依赖：{check}")
            require(isinstance(definition["implemented"], bool), "CP_SCHEMA", f"检查实现状态不是布尔值：{check}")
            require(definition["kind"] in ("automatic", "scenario", "human", "review"), "CP_SCHEMA", check)
            if definition["kind"] == "review":
                require(check == "semantic-review" and definition["implemented"] and stage == "current" and
                        not any(field in definition for field in ("argv", "environment_argv")),
                        "CP_SCHEMA", "普通语义评审不能重标其他责任或执行自带命令")
            if definition["implemented"]:
                require(definition["kind"] == "review" or
                        definition["kind"] == "automatic" and definition.get("argv"),
                        "CP_MISSING_VERIFICATION", f"检查无可执行命令：{check}")
            for field in ("argv", "environment_argv"):
                if field in definition:
                    require(isinstance(definition[field], list) and definition[field] and
                            all(isinstance(part, str) and part for part in definition[field]),
                            "CP_SCHEMA", f"检查命令须为非空参数数组：{check}: {field}")
            if "environment_argv" in definition:
                require(definition["implemented"] and definition["kind"] == "automatic", "CP_SCHEMA",
                        f"只有已实现自动检查能登记环境探针：{check}")

    def validate_maintenance(self):
        """校准只标记已实现的当前自动检查，不能重标产品或人工验收义务。"""
        policy = self.catalog.get("maintenance", {"inputs": [], "checks": []})
        require(isinstance(policy, dict) and set(policy) == {"inputs", "checks"},
                "CP_MAINTENANCE_POLICY", "校准配置只登记输入范围和检查身份")
        inputs, checks = policy["inputs"], policy["checks"]
        require(isinstance(inputs, list) and isinstance(checks, list) and bool(inputs) == bool(checks) and
                all(isinstance(item, str) and item and not item.startswith(("/", "\\")) and
                    ".." not in item.split("/") and "\\" not in item for item in inputs) and
                len(inputs) == len(set(inputs)), "CP_MAINTENANCE_POLICY", "校准输入须为唯一仓库内相对模式")
        require(all(isinstance(check, str) and check in self.catalog["checks"] and
                    self.catalog["checks"][check]["kind"] == "automatic" and
                    self.catalog["checks"][check]["implemented"] and
                    self.catalog["checks"][check].get("requires_test_count") is True and
                    self.catalog["checks"][check].get("stage", "current") == "current" for check in checks) and
                len(checks) == len(set(checks)), "CP_MAINTENANCE_POLICY", "校准须引用唯一、已实现的当前自动检查")
        suites = {check for check, definition in self.catalog["checks"].items() if
                  any(part == "tools/engineering_gate" or part.startswith("tools/engineering_gate/test_")
                      for part in definition.get("argv", []))}
        require(suites <= set(checks), "CP_MAINTENANCE_POLICY", "工程反例的校准登记缺失，不能静默关闭条件 CI")
        self.maintenance = policy

    def read_tasks(self):
        """每个任务只允许一个事实块，校验目标、输入、前沿及验收来源。"""
        for identifier, document in self.catalog["tasks"].items():
            require(document in self.documents and self.documents[document]["kind"] == "task",
                    "CP_TASK_POINTER", f"任务未指向任务正文：{identifier}")
            blocks = re.findall(r"^```control-plane-task\s*\n(.*?)^```\s*$", self.texts[document], re.M | re.S)
            require(len(blocks) == 1, "CP_TASK_RECORD_COUNT", f"任务事实块须唯一：{identifier}")
            task = parse_json(blocks[0], identifier)
            require(task.get("id") == identifier, "CP_TASK_POINTER", f"任务对象不匹配：{identifier}")
            historical = task.get("acceptance") is not None
            require(re.fullmatch(r"[0-9a-f]{40,64}", task.get("base", "")), "CP_GIT_INPUT", "任务缺少精确 Git 起点")
            require(task.get("goals"), "CP_GOAL_MISSING", identifier)
            for ref in task["goals"]:
                self.reference(ref, historical=historical)
                require(self.documents[ref.split("#")[0]]["category"] == "product", "CP_GOAL_MISSING", f"目标不是产品入口：{ref}")
            self.reference(task["unit"], historical=historical)
            require(task["type"] in self.catalog["task_types"], "CP_TASK_TYPE", identifier)
            self.scopes(task["scopes"])
            require(task["consumers"] and task["frontier"] and task["verification"], "CP_TASK_INCOMPLETE", identifier)
            for ref in task["design_refs"] + task["extra_refs"]:
                self.reference(ref, historical=historical)
            for item in task["verification"]:
                require(item["check"] in self.catalog["checks"] and item.get("criterion"),
                        "CP_MISSING_VERIFICATION", f"任务缺少验证及标准：{identifier}")
            for key in ("pending", "deferred"):
                require(isinstance(task[key], list), "CP_TASK_INCOMPLETE", identifier)
            self.acceptance(task)
            self.tasks[identifier] = task
        current = self.catalog.get("current_task")
        outstanding = any(not item["acceptance"] or item["pending"] or not item.get("closing") or
                          item["closing"]["status"] != "passed" for item in self.tasks.values())
        require(current is not None or not outstanding, "CP_TASK_POINTER", "活动或待收尾任务缺少当前指针")
        if current is not None:
            require(current in self.tasks, "CP_TASK_POINTER", f"当前任务不存在：{current}")
            task = self.tasks[current]
            require(not task["acceptance"] or task["pending"] or not task.get("closing") or task["closing"]["status"] != "passed",
                    "CP_TASK_POINTER", "当前任务指向已验收且无待处理事项的归档任务")

    def acceptance(self, task):
        """人工验收与技术收尾分别登记；这里只核对关联，不证明来源真实性或 CI 当前状态。"""
        accepted = task["acceptance"]
        if accepted is not None:
            require(isinstance(accepted, dict) and all(accepted.get(key) for key in ("actor", "date", "source", "functional_sha", "scope")),
                    "CP_ACCEPTANCE_SOURCE", "缺少人工验收来源、日期、范围或精确输入")
            require(accepted["actor"].lower() not in ("agent", "codex", "ai") and re.fullmatch(r"[0-9a-f]{40,64}", accepted["functional_sha"]),
                    "CP_ACCEPTANCE_SOURCE", "执行者自述或错误 SHA 不能产生人工验收")
            self.reference(accepted["source"], historical=True)
        closing = task.get("closing")
        if closing is not None:
            require(accepted is not None and closing["status"] in ("pending", "failed", "passed"), "CP_CLOSING_EVIDENCE", "收尾缺少功能验收或状态无效")
            if closing["status"] == "passed":
                require(all(closing.get(key) for key in ("record_sha", "remote_sha", "ci_sha", "source")) and
                        re.fullmatch(r"[0-9a-f]{40,64}", closing["record_sha"]) and
                        closing["record_sha"] == closing["remote_sha"] == closing["ci_sha"],
                        "CP_CLOSING_EVIDENCE", "收尾输入、远端与 CI 记录不匹配")
                self.reference(closing["source"], historical=True)
            else:
                require(closing.get("reason"), "CP_CLOSING_EVIDENCE", "失败或待处理收尾须保留原因")

    def link(self, source, ref, label=None, historical=False):
        target = self.reference(ref, historical=historical)
        file, _, anchor = target.partition("#")
        relative = os.path.relpath(self.root / file, (self.root / source).parent).replace(os.sep, "/")
        return f"[{label or ref}]({relative}#{anchor})"

    def indices(self):
        """从身份、触发和唯一任务事实派生索引，不保存另一份任务状态。"""
        files = {}
        root_index = "docs/index.md"
        core = [key for key, doc in self.documents.items()
                if doc["kind"] in ("rules", "entry") and not doc.get("replaced_by")]
        order = self.catalog.get("reading_order", core)
        require(isinstance(order, list) and all(isinstance(key, str) for key in order) and
                len(order) == len(set(order)) and set(order) == set(core),
                "CP_READING_ORDER", "人工阅读顺序须恰好覆盖有效产品、规范与工作入口，不混入任务或历史")
        summary = ["# 文档总索引", "", MARKER, "", "Agent 只读取任务命中的小节；索引存在不代表门禁或人工验收通过。", "",
                   "## 人工阅读顺序", "", "首次通读按编号阅读；编号是阅读顺序，文档 ID 才是稳定身份。后续设计和当前进度从分类及任务入口补读。此顺序不扩大 Agent 的最低阅读清单。", ""]
        for number, identifier in enumerate(order, 1):
            doc = self.documents[identifier]
            relative = os.path.relpath(self.root / doc["path"], (self.root / root_index).parent).replace(os.sep, "/")
            title = self.headings[identifier][0]["title"]
            summary.append(f"{number}. [{number:02d} · {identifier} · {title}]({relative})")
        summary += ["", "## 分类入口", ""]
        for name, category in self.catalog["categories"].items():
            path = category["index"]
            relative = os.path.relpath(self.root / path, (self.root / root_index).parent).replace(os.sep, "/")
            summary.append(f"- [{category['title']}]({relative})")
            lines = [f"# {category['title']}索引", "", MARKER, ""]
            if name != "tasks":
                lines += ["## 正文与按需小节", ""]
            for identifier, doc in self.documents.items():
                if doc["category"] != name or doc.get("replaced_by"):
                    continue
                if name == "tasks":
                    continue  # 默认入口只派生活动及待办；历史证据另有按需入口。
                relative = os.path.relpath(self.root / doc["path"], (self.root / path).parent).replace(os.sep, "/")
                lines.append(f"- [{identifier}]({relative})（{doc['kind']}）")
                for section in self.headings[identifier]:
                    if section["level"] != 2:
                        continue
                    ref = identifier + "#" + section["anchor"]
                    triggers = sorted({route.get("trigger", "、".join(route["scopes"])) for route in self.catalog["routes"] if ref in route["refs"]})
                    caption = "、".join(triggers) if triggers else "按目标定位；不默认加载"
                    lines.append(f"  - {caption} → {self.link(path, ref, section['title'])}")
            if name == "tasks":
                lines += ["", "## 活动与待处理", ""]
                for identifier, task in self.tasks.items():
                    if not task["acceptance"]:
                        lines.append(f"- {self.link(path, task['unit'], identifier)}：{task['frontier']}")
                    elif not task.get("closing") or task["closing"]["status"] != "passed":
                        reason = (task.get("closing") or {}).get("reason", "必要收尾尚未完成")
                        lines.append(f"- {self.link(path, task['unit'], identifier)}：收尾待处理；{reason}")
                    for pending in task["pending"]:
                        lines.append(f"- {identifier} 待处理：{pending}")
                lines += ["", "## 归档查询", ""]
                if category.get("archive"):
                    archive_path = category["archive"]
                    relative = os.path.relpath(self.root / archive_path, (self.root / path).parent).replace(os.sep, "/")
                    lines.append(f"[按需查询归档任务与历史证据]({relative})；默认不加载。")
                    archive = ["# 归档任务与历史证据", "", MARKER, "", "## 已登记验收的任务", ""]
                    accepted = [task for task in self.tasks.values() if task["acceptance"]]
                    archive += [f"- {self.link(archive_path, task['unit'], task['id'])}：验收输入 `{task['acceptance']['functional_sha']}`；来源及收尾须独立核对。" for task in accepted] or ["暂无已获人工验收的交付单元。"]
                    archive += ["", "## 按需证据", ""]
                    archive += [f"- {self.link(archive_path, key + '#' + self.headings[key][0]['anchor'], key)}" for key, doc in self.documents.items() if doc["kind"] == "evidence"]
                    replaced = [(key, doc) for key, doc in self.documents.items() if doc.get("replaced_by")]
                    if replaced:
                        archive += ["", "## 被替代正文", ""]
                        archive += [f"- {self.link(archive_path, key + '#' + self.headings[key][0]['anchor'], key, historical=True)}：由 {doc['replaced_by']} 替代，不能作为新任务现行规则。" for key, doc in replaced]
                    files[archive_path] = "\n".join(archive).rstrip("\n") + "\n"
                else:
                    lines.append("按所属任务检索验收来源与证据；来源真实性及收尾仍须独立核对。")
            files[path] = "\n".join(lines).rstrip("\n") + "\n"
        current = self.catalog.get("current_task")
        summary += ["", "## 当前任务", ""]
        if current:
            summary.append(self.link(root_index, self.tasks[current]["unit"], current))
        else:
            summary.append("无当前活动任务。")
        files[root_index] = "\n".join(summary).rstrip("\n") + "\n"
        require(len(files) == len(self.catalog["categories"]) + 1 + sum(bool(item.get("archive")) for item in self.catalog["categories"].values()),
                "CP_CATEGORY", "分类或归档索引路径重复")
        return files

    def write_indices(self):
        """仅机械生成已登记的索引；已有非派生内容不覆盖，不触碰正文和 Git。"""
        files = self.indices()
        for relative in files:
            require(relative.startswith("docs/") and relative.endswith(".md"),
                    "CP_UNSAFE_PATH", f"只能生成已登记的文档索引：{relative}")
            path = safe_path(self.root, relative)
            require(not path.exists() or MARKER in read_text(path), "CP_INDEX_OWNERSHIP", f"不能覆盖人工正文：{relative}")
        changed = []
        for relative, content in files.items():
            path = safe_path(self.root, relative)
            if not path.exists() or read_text(path) != content:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                changed.append(relative)
        return {"ok": True, "diagnostics": [], "generated": changed, "proof_scope": "derived-views-only"}

    def check(self):
        """完整结构检查覆盖索引、正文、锚点和任务记录；不替代语义或产品门禁。"""
        errors = []
        def note(code, message):
            errors.append({"code": code, "message": message})

        indices = self.indices()
        expected = {doc["path"] for doc in self.documents.values()} | set(indices)
        existing = {str(path.relative_to(self.root)) for path in (self.root / "docs").rglob("*.md")}
        existing |= {path.name for path in self.root.glob("*.md")}
        for path in sorted(existing - expected):
            note("CP_UNINDEXED_DOCUMENT", path)
        for path, text in indices.items():
            target = safe_path(self.root, path)
            if not target.exists() or read_text(target) != text:
                note("CP_INDEX_DRIFT", path)
        routed = {ref for route in self.catalog["routes"] for ref in route["refs"]}
        for identifier, doc in self.documents.items():
            if doc["kind"] != "rules" or doc.get("replaced_by"):
                continue
            for section in self.headings[identifier]:
                if section["level"] == 2 and section["title"] != "适用范围与阅读入口":
                    ref = identifier + "#" + section["anchor"]
                    if ref not in routed:
                        note("CP_UNROUTED_SECTION", ref)
        links = 0
        for relative in sorted(existing):
            path = safe_path(self.root, relative)
            text = read_text(path)
            if not text.endswith("\n") or any(line.rstrip() != line for line in text.splitlines()):
                note("CP_MARKDOWN_FORMAT", relative)
            _, prose = markdown(text)
            width = None
            for line in prose.splitlines():
                if line.startswith("|") and line.endswith("|"):
                    count = len(re.split(r"(?<!\\)\|", line)) - 2
                    if width is not None and width != count:
                        note("CP_TABLE_COLUMNS", relative)
                    width = count
                else:
                    width = None
            for target in re.findall(r"!?\[[^\]\n]*\]\(([^\s)]+)\)", prose):
                if urllib.parse.urlsplit(target).scheme:
                    continue  # 外部内容、可用性与远端锚点不在本检查的证明范围。
                links += 1
                decoded = urllib.parse.unquote(target)
                file, marker, anchor = decoded.partition("#")
                resolved = (path.parent / file).resolve() if file else path
                if not resolved.is_relative_to(self.root) or not resolved.is_file():
                    note("CP_LINK_TARGET", f"{relative}: {target}")
                elif marker and anchor not in {item["anchor"] for item in markdown(read_text(resolved))[0]}:
                    note("CP_LINK_ANCHOR", f"{relative}: {target}")
        return {"ok": not errors, "proof_scope": "document-structure-only", "diagnostics": errors,
                "counts": {"documents": len(self.documents), "markdown": len(existing), "local_links": links}}

    def classify(self, relative):
        """路由及 CI 使用同一实际路径分类，未知或冲突用途不能静默跳过。"""
        safe_path(self.root, relative)
        matches = [item for item in self.catalog["classifiers"] if fnmatch.fnmatchcase(relative, item["glob"])]
        require(matches, "CP_UNKNOWN_FILE", f"实际文件用途未登记：{relative}")
        require(len({item["purpose"] for item in matches}) == 1, "CP_CONFLICTING_PURPOSE", f"文件用途冲突：{relative}")
        return matches

    def maintenance_changed(self, paths, base, read_git=git):
        """按实际差异校准；单纯登记任务或设计不等于变更规则与执行机制。"""
        rule_paths = {doc["path"] for doc in self.documents.values() if doc["kind"] == "rules"}
        matched = {path for path in paths if path in rule_paths or any(fnmatch.fnmatchcase(path, pattern)
                                                                      for pattern in self.maintenance["inputs"])}
        if CATALOG in matched and set(base) != {"0"}:
            if read_git(self.root, "ls-tree", "--name-only", base, "--", CATALOG).strip():
                try:
                    previous = parse_json(read_git(self.root, "show", base + ":" + CATALOG), CATALOG)
                except Rejected:
                    return True

                def governance(catalog):
                    metadata = {"documents", "tasks", "current_task", "categories", "reading_order"}
                    result = {key: value for key, value in catalog.items() if key not in metadata}
                    result["rule_documents"] = {key: value for key, value in catalog.get("documents", {}).items()
                                                if value.get("kind") == "rules"}
                    return result

                if governance(previous) == governance(self.catalog):
                    matched.remove(CATALOG)
        return bool(matched)

    def product_changed(self, paths, base=None, read_git=git):
        """产品输入或共同检查实现变化须选择真实产品执行；尚未接入则明确拒绝。"""
        policy = self.catalog.get("product_ci")
        if policy is not None:
            require(isinstance(policy, dict) and set(policy) == {"inputs", "checks"} and
                    isinstance(policy["inputs"], list) and policy["inputs"] and
                    all(isinstance(value, str) and value for value in policy["inputs"]) and
                    isinstance(policy["checks"], list) and policy["checks"] and
                    all(name in self.catalog["checks"] and self.catalog["checks"][name]["implemented"] and
                        self.catalog["checks"][name].get("stage") == "product" for name in policy["checks"]),
                    "CP_CI_PRODUCT_UNWIRED", "产品 CI 选择合同缺少已实现的产品检查")
        classified = [(path, self.classify(path)[0]["purpose"]) for path in paths if path]
        metadata_only = base is not None and any(path == CATALOG for path, _ in classified) and not self.maintenance_changed(
            [CATALOG], base, read_git=read_git)
        rule_paths = {doc["path"] for doc in self.documents.values() if doc["kind"] == "rules"}
        selected = any(purpose in ("production", "test") or (policy and path in rule_paths) or
                       (not (path == CATALOG and metadata_only) and policy and
                        any(fnmatch.fnmatchcase(path, pattern) for pattern in policy["inputs"]))
                       for path, purpose in classified)
        require(not selected or policy is not None, "CP_CI_PRODUCT_UNWIRED",
                "真实产品 CI 尚未接入，不能仅以文档／校准检查放行")
        return bool(selected)

    def ci_plan(self, base):
        """按实际差异选择校准与产品检查；选择本身不代表交付或人工验收。"""
        require(re.fullmatch(r"[0-9a-f]{40,64}", base), "CP_GIT_INPUT", "CI 起点须为事件精确 SHA")
        paths = (sorted(set(git(self.root, "ls-files", "--cached", "--others", "--exclude-standard", "-z").split("\0")) - {""})
                 if set(base) == {"0"} else changed_paths(self.root, base))
        product = self.product_changed(paths, base)
        selected = self.maintenance["checks"] if self.maintenance_changed(paths, base) else []
        return {"ok": True, "proof_scope": "change-based-ci-selection-only", "base": base,
                "changed_paths": paths, "maintenance_required": bool(selected),
                "maintenance_checks": sorted(selected), "product_required": product,
                "product_checks": sorted(self.catalog["product_ci"]["checks"]) if product else [],
                "human_acceptance": False, "diagnostics": []}

    def route(self, identifier):
        """合并任务计划、实际差异与语义补充，保留每条引用的命中原因。"""
        require(identifier in self.tasks, "CP_TASK_POINTER", f"不存在任务：{identifier}")
        task = self.tasks[identifier]
        reasons = {}
        for scope in self.catalog["task_types"][task["type"]] + task["scopes"]:
            reasons.setdefault(scope, set()).add(f"任务 {identifier} 的类型／计划范围")
        paths = changed_paths(self.root, task["base"])
        product_change = False
        for relative in paths:
            matches = self.classify(relative)
            product_change |= matches[0]["purpose"] in ("production", "test")
            for item in matches:
                for scope in item["scopes"]:
                    reasons.setdefault(scope, set()).add(f"实际变更 {relative}（{item['purpose']}）")
        rules = {}
        checks = {item["check"] for item in task["verification"]}
        for scope, source in reasons.items():
            routes = [item for item in self.catalog["routes"] if scope in item["scopes"]]
            require(routes, "CP_MISSING_ROUTE", f"范围没有路由：{scope}")
            for route in routes:
                checks.update(route["checks"])
                for ref in route["refs"]:
                    item = rules.setdefault(ref, {"ref": ref, "target": self.reference(ref), "reasons": set(), "checks": set()})
                    item["reasons"].add(f"scope:{scope}")
                    item["checks"].update(route["checks"])
        for ref in dict.fromkeys(task["goals"] + task["design_refs"] + task["extra_refs"]):
            bindings = {check for route in self.catalog["routes"] if ref in route["refs"] for check in route["checks"]}
            if not bindings:
                require(self.documents[ref.split("#")[0]]["kind"] != "rules", "CP_MISSING_VERIFICATION", f"补充规则无验证映射：{ref}")
                bindings = {item["check"] for item in task["verification"]}
            checks.update(bindings)
            item = rules.setdefault(ref, {"ref": ref, "target": self.reference(ref), "reasons": set(), "checks": set()})
            if ref in task["goals"]:
                item["reasons"].add("当前任务的有效目标")
            if ref in task["design_refs"]:
                item["reasons"].add("当前任务的现行设计与合同")
            if ref in task["extra_refs"]:
                item["reasons"].add("Agent 补充语义影响；保留登记的必要验证")
            item["checks"].update(bindings)
        checks = self.verification_checks(checks)
        calibration = checks & set(self.maintenance["checks"])
        omitted = calibration - {item["check"] for item in task["verification"]} if not self.maintenance_changed(paths, task["base"]) else set()
        checks -= omitted
        phase = "control-plane" if task["type"] in ("governance", "documentation") and not product_change else "product"
        deferred = sorted(check for check in checks if phase == "control-plane" and
                          self.catalog["checks"][check].get("stage", "current") == "product")
        return {"ok": True, "proof_scope": "minimum-reading-and-verification-plan", "task": identifier,
                "scopes": sorted(reasons), "changed_paths": paths, "required_checks": sorted(checks - set(deferred)),
                "maintenance_checks": sorted(calibration - omitted),
                "not_applicable_checks": [{"id": check, "executed": False,
                                            "reason": "本次实际变更未触及登记的校准输入，任务也未显式要求该校准"}
                                           for check in sorted(omitted)],
                "verification_phase": phase,
                "deferred_checks": [{"id": check, "stage": "product", "status": "not_executed", "executed": False,
                                     "mechanism_checks": sorted(self.catalog["checks"][check]["mechanism_checks"]),
                                     "reason": self.catalog["checks"][check]["reason"]} for check in deferred],
                "scope_reasons": {scope: sorted(source) for scope, source in sorted(reasons.items())},
                "rules": [dict(item, reasons=sorted(item["reasons"]), checks=sorted(self.verification_checks(item["checks"])))
                          for _, item in sorted(rules.items())],
                "diagnostics": [], "semantic_review_proven": False}

    def context(self, identifier, references=()):
        """默认输出摘要和目录，正文显式按小节读取；局部加载不缩小全部验证责任。"""
        before, _ = input_snapshot(self.root)
        plan = self.route(identifier)
        requested = set(references)
        minimum = {item["ref"] for item in plan["rules"]}
        require(requested <= minimum, "CP_CONTEXT_SELECTION", "请求小节不在当前最低清单；先核对语义影响及有效引用")
        candidates = []
        for item in plan["rules"]:
            if item["ref"] not in requested:
                continue
            document, anchor = item["ref"].split("#", 1)
            headings, lines = self.headings[document], self.texts[document].splitlines()
            heading = next(value for value in headings if value["anchor"] == anchor)
            end = next((value["line"] - 1 for value in headings if value["line"] > heading["line"] and
                        value["level"] <= heading["level"]), len(lines))
            candidates.append({"path": self.documents[document]["path"], "start_line": heading["line"],
                               "end_line": end, "refs": [item["ref"]],
                               "text": "\n".join(lines[heading["line"] - 1:end])})
        sections = []
        for item in sorted(candidates, key=lambda value: (value["path"], value["start_line"], -value["end_line"])):
            if sections and sections[-1]["path"] == item["path"] and sections[-1]["end_line"] >= item["end_line"]:
                sections[-1]["refs"] += item["refs"]
            else:
                sections.append(item)
        after, _ = input_snapshot(self.root)
        require(before == after, "CP_INPUT_CHANGED", "生成上下文期间输入变化，须重新定位")
        task = self.tasks[identifier]
        brief = {key: task[key] for key in ("goals", "unit", "consumers", "base", "design_refs", "frontier", "pending", "deferred")}
        manifest = {key: value for key, value in plan.items() if key not in ("rules", "scope_reasons", "changed_paths")}
        manifest["rules"] = [{key: item[key] for key in ("ref", "target", "reasons")} for item in plan["rules"]]
        manifest["loaded_rule_checks"] = {item["ref"]: item["checks"] for item in plan["rules"] if item["ref"] in requested}
        return dict(manifest, proof_scope="minimum-task-context-only", input=before, task_brief=brief, sections=sections,
                    loaded_refs=sorted(requested), remaining_refs=sorted(minimum - requested))

    def verification_checks(self, checks):
        """产品结果保留为独立义务，其第五层机制责任始终进入当前最低清单。"""
        return set(checks) | {mechanism for check in checks
                             for mechanism in self.catalog["checks"][check].get("mechanism_checks", [])}

    def fixed_argv(self, argv):
        """仅展开治理配置中的固定入口，不从任务接受命令。"""
        replacements = {"@python": sys.executable, "@entry": str(safe_path(self.root, ENTRY))}
        return [replacements.get(part, part) for part in argv]

    def execution_environment(self, required):
        """核对必要检查的实际外部运行时；缺失不回退为只有 Python 的报告。"""
        environment = {"python": sys.version, "executable": sys.executable}
        for check in required:
            argv = self.catalog["checks"][check].get("environment_argv")
            if not argv:
                continue
            try:
                process = subprocess.run(self.fixed_argv(argv), cwd=self.root, capture_output=True, text=True, timeout=30)
            except (OSError, subprocess.TimeoutExpired) as error:
                raise Rejected("CP_ENVIRONMENT_INPUT", f"无法核对必要工具环境：{check}") from error
            require(process.returncode == 0, "CP_ENVIRONMENT_INPUT", f"必要工具环境失败：{check}")
            report = parse_json(process.stdout, check + " environment")
            require(report.get("ok") and isinstance(report.get("environment"), dict) and report["environment"],
                    "CP_ENVIRONMENT_INPUT", f"必要工具环境为空或未验证：{check}")
            environment.setdefault("checks", {})[check] = report["environment"]
        return environment

    def validate_review(self, review, route, current, inputs):
        """仅核对普通评审的当前对象、覆盖及材料，不证明判断正确或产生人工决定。"""
        require(isinstance(review, dict), "CP_REVIEW_EVIDENCE", "评审记录须是结构化对象")
        require(review.get("task") == route["task"] and review.get("input") == current,
                "CP_STALE_REVIEW", "评审不属于当前任务／输入")
        require(review.get("actor") in ("agent", "human"), "CP_REVIEW_EVIDENCE", "评审须声明实际执行者类别")
        minimum = {item["ref"] for item in route["rules"] if "semantic-review" in item["checks"]}
        coverage = review.get("coverage")
        require(isinstance(coverage, list) and coverage and all(isinstance(item, dict) and
                isinstance(item.get("refs"), list) and item["refs"] and
                all(isinstance(ref, str) for ref in item["refs"]) for item in coverage),
                "CP_REVIEW_COVERAGE", "评审缺少当前规则覆盖")
        refs = [ref for item in coverage for ref in item["refs"]]
        require(len(refs) == len(set(refs)) and set(refs) == minimum,
                "CP_REVIEW_COVERAGE", "评审遗漏、重复或混入当前最低清单以外的规则")
        for item in coverage:
            require(isinstance(item.get("conclusion"), str) and item["conclusion"].strip() and
                    isinstance(item.get("evidence"), list) and item["evidence"],
                    "CP_REVIEW_EVIDENCE", "每组评审须有结论和可定位的源码／场景材料")
            for evidence in item["evidence"]:
                require(isinstance(evidence, dict) and isinstance(evidence.get("path"), str) and
                        isinstance(evidence.get("sha256"), str) and
                        re.fullmatch(r"[0-9a-f]{64}", evidence.get("sha256", "")),
                        "CP_REVIEW_EVIDENCE", "评审材料须有路径及字节摘要")
                relative = evidence["path"]
                path = pathlib.Path(relative)
                if path.is_absolute():
                    require(not path.resolve().is_relative_to(self.root), "CP_REVIEW_EVIDENCE",
                            "仓库材料使用维护输入中的相对路径")
                else:
                    path = safe_path(self.root, relative)
                    require(inputs.get(relative) == evidence["sha256"], "CP_REVIEW_EVIDENCE", "材料不是当前维护输入")
                require(path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == evidence["sha256"],
                        "CP_REVIEW_EVIDENCE", f"评审材料缺失或已变化：{relative}")
        require(isinstance(review.get("findings"), list) and not review["findings"],
                "CP_REVIEW_REWORK", "评审存在未整改发现或未声明发现清单")
        require(isinstance(review.get("pending_decisions"), list) and not review["pending_decisions"],
                "CP_REVIEW_DECISION_REQUIRED", "仍有重大取舍待人工决定或未核对决定责任")

    def delivery(self, identifier, query_path=None):
        """读取精确 Git／CI 对象；校准及对象检查均不生成实际人工验收。"""
        if __package__:
            from .delivery_evidence import inspect_delivery
        else:
            from delivery_evidence import inspect_delivery
        return inspect_delivery(self, identifier, query_path)

    def run(self, identifier, review_path=None, delivery_path=None):
        """真实执行自动检查并核对评审关联；未实现、失败、空执行或输入变化均不放行。"""
        route = self.route(identifier)
        before, inputs = input_snapshot(self.root)
        environment = self.execution_environment(route["required_checks"])
        results, errors, review_text = [], [], None
        product_executions = {}
        for check in route["required_checks"]:
            definition = self.catalog["checks"][check]
            if definition["kind"] == "review":
                try:
                    require(review_path is not None, "CP_REVIEW_REQUIRED", "缺少普通语义评审记录；不自动生成结论")
                    require(review_path.is_absolute() and not review_path.resolve().is_relative_to(self.root),
                            "CP_UNSAFE_PATH", "评审记录放在仓库外的绝对路径，避免进入自身输入")
                    review_text = read_text(review_path)
                    review = parse_json(review_text, str(review_path))
                    self.validate_review(review, route, before, inputs)
                    results.append({"id": check, "exit_code": 0, "proof_scope": "review-record-integrity-only", "review": review})
                except Rejected as error:
                    errors.append(error.diagnostic)
                continue
            if not definition["implemented"]:
                errors.append({"code": "CP_UNIMPLEMENTED_CHECK", "message": f"待落实 {definition['kind']} 责任：{check}"})
                continue
            argv = self.fixed_argv(definition["argv"])
            if check == "delivery-evidence":
                argv = [identifier if part == "@task" else part for part in argv]
                if delivery_path is not None:
                    argv += ["--query", str(delivery_path)]
            try:
                product = definition.get("stage") == "product"
                execution_key = tuple(argv)
                if product and execution_key in product_executions:
                    process = product_executions[execution_key]
                else:
                    process = subprocess.run(argv, cwd=self.root, capture_output=True, text=True,
                                             timeout=1800 if product else 180)
                    if product:
                        product_executions[execution_key] = process
                executed = {"id": check, "argv": argv, "exit_code": process.returncode,
                            "stdout": process.stdout, "stderr": process.stderr}
                results.append(executed)
                if definition.get("requires_test_count") and re.search(r"Ran 0 tests\b", process.stderr + process.stdout):
                    errors.append({"code": "CP_EMPTY_EXECUTION", "message": f"没有实际测试：{check}"})
                elif process.returncode != 0:
                    errors.append({"code": "CP_CHECK_FAILED", "message": check})
                elif definition.get("requires_test_count") and not re.search(r"Ran [1-9][0-9]* tests?\b", process.stderr + process.stdout):
                    errors.append({"code": "CP_EMPTY_EXECUTION", "message": f"没有实际测试：{check}"})
                elif definition.get("requires_test_count") and re.search(r"skipped=[1-9][0-9]*", process.stderr + process.stdout):
                    errors.append({"code": "CP_SKIPPED_EXECUTION", "message": f"必需测试被跳过：{check}"})
                elif check == "doc-integrity":
                    proof = parse_json(process.stdout, check)
                    if not proof.get("ok") or not proof.get("counts", {}).get("documents"):
                        errors.append({"code": "CP_EMPTY_EXECUTION", "message": check})
                elif product:
                    proof = parse_json(process.stdout, check)
                    if not (proof.get("ok") and proof.get("input") == before and
                            proof.get("proof_scope") == "minimal-engineering-real-product" and
                            proof.get("checks", {}).get(check, {}).get("ok") is True):
                        errors.append({"code": "CP_PRODUCT_EVIDENCE", "message": "缺少当前输入的真实产品检查：" + check})
            except (OSError, subprocess.TimeoutExpired) as error:
                errors.append({"code": "CP_EXECUTION_UNAVAILABLE", "message": f"{check}: {type(error).__name__}"})
        after, _ = input_snapshot(self.root)
        if before != after:
            errors.append({"code": "CP_INPUT_CHANGED", "message": "执行期间输入变化，结果不能代表当前候选"})
        if environment != self.execution_environment(route["required_checks"]):
            errors.append({"code": "CP_ENVIRONMENT_CHANGED", "message": "执行期间必要工具或运行时变化，结果不能代表当前候选"})
        if review_text is not None:
            try:
                require(read_text(review_path) == review_text, "CP_REVIEW_CHANGED", "执行期间外部评审记录变化")
                self.validate_review(parse_json(review_text, str(review_path)), route, before, inputs)
            except Rejected as error:
                if not any(item["code"] == error.diagnostic["code"] for item in errors):
                    errors.append({"code": "CP_REVIEW_CHANGED", "message": "执行期间评审记录或材料失效：" + error.diagnostic["code"]})
        return {"ok": not errors, "proof_scope": "configured-checks-only", "task": identifier,
                "input": before, "inputs": inputs, "required_checks": route["required_checks"], "checks": results,
                "verification_phase": route["verification_phase"], "deferred_checks": route["deferred_checks"],
                "maintenance_checks": route["maintenance_checks"], "not_applicable_checks": route["not_applicable_checks"],
                "environment": environment,
                "diagnostics": errors, "human_acceptance": False}

    def verify_receipt(self, identifier, receipt_path):
        """只核对历史报告的对象及覆盖；报告可被伪造，不据此生成可信执行或人工验收。"""
        require(not receipt_path.resolve().is_relative_to(self.root), "CP_UNSAFE_PATH", "报告放在仓库外，避免进入自身输入")
        report = parse_json(read_text(receipt_path), str(receipt_path))
        current, inputs = input_snapshot(self.root)
        require(report.get("task") == identifier and report.get("input") == current and report.get("inputs") == inputs,
                "CP_STALE_INPUT", "报告不属于当前任务／输入")
        route = self.route(identifier)
        required = route["required_checks"]
        require(report.get("verification_phase") == route["verification_phase"] and
                report.get("deferred_checks") == route["deferred_checks"], "CP_STAGE_COVERAGE",
                "报告阶段或后续未执行责任与当前实际范围不匹配")
        require(report.get("maintenance_checks") == route["maintenance_checks"] and
                report.get("not_applicable_checks") == route["not_applicable_checks"], "CP_MAINTENANCE_COVERAGE",
                "报告的校准范围或未执行依据与当前实际变更不符")
        require(report.get("environment") == self.execution_environment(required),
                "CP_STALE_ENVIRONMENT", "报告运行时不属于当前执行环境")
        require(report.get("required_checks") == required and len(report["checks"]) == len(required) and
                {item["id"] for item in report["checks"]} == set(required),
                "CP_MISSING_EXECUTION", "报告遗漏必要检查或仍使用旧覆盖")
        require(report.get("ok") and not report.get("diagnostics") and all(item["exit_code"] == 0 for item in report["checks"]),
                "CP_FAILED_EXECUTION", "报告包含失败、未验证或跳过")
        for item in report["checks"]:
            if self.catalog["checks"][item["id"]]["kind"] == "review":
                self.validate_review(item.get("review", {}), route, current, inputs)
        return {"ok": True, "diagnostics": [], "proof_scope": "record-integrity-only", "human_acceptance": False}


def main():
    """返回机器可核对的 JSON 与非零拒绝码；未支持语法和缺失输入明确失败。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check")
    index = sub.add_parser("index")
    index.add_argument("--write", action="store_true", help="机械生成派生索引，不修改正文或 Git")
    ci = sub.add_parser("ci-plan")
    ci.add_argument("--base", required=True)
    for command in ("route", "context", "run", "verify-receipt", "delivery"):
        option = sub.add_parser(command)
        option.add_argument("--task", required=True)
        if command == "context":
            option.add_argument("--ref", action="append", default=[], help="显式加载最低清单中的小节；可重复，默认只返回目录")
        if command == "run":
            option.add_argument("--review", type=pathlib.Path, help="仓库外的当前输入语义评审记录；不是人工验收")
            option.add_argument("--delivery-query", type=pathlib.Path, help="仓库外的精确 Git／CI 对象选择；不是通过报告")
        if command == "delivery":
            option.add_argument("--query", type=pathlib.Path, help="精确功能 SHA、目标分支与真实 CI run ID")
        if command == "verify-receipt":
            option.add_argument("--receipt", type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        plane = ControlPlane(args.root)
        if args.command == "check":
            result = plane.check()
        elif args.command == "index":
            result = plane.write_indices() if args.write else {"ok": True, "diagnostics": [], "files": plane.indices()}
        elif args.command == "route":
            result = plane.route(args.task)
        elif args.command == "context":
            result = plane.context(args.task, args.ref)
        elif args.command == "ci-plan":
            result = plane.ci_plan(args.base)
        elif args.command == "run":
            result = plane.run(args.task, args.review, args.delivery_query)
        elif args.command == "delivery":
            result = plane.delivery(args.task, args.query)
        else:
            result = plane.verify_receipt(args.task, args.receipt)
    except Rejected as error:
        result = {"ok": False, "diagnostics": [error.diagnostic]}
    except (OSError, UnicodeError) as error:
        result = {"ok": False, "diagnostics": [{"code": "CP_INPUT_UNREADABLE", "message": type(error).__name__}]}
    except (KeyError, TypeError, ValueError, AttributeError) as error:
        result = {"ok": False, "diagnostics": [{"code": "CP_SCHEMA", "message": f"输入结构不符合已声明格式：{type(error).__name__}"}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
