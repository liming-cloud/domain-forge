"""只读核对原生 Git 与 GitHub Actions；不生成批准，也不把协议夹具当作 CI。"""

import hashlib
import json
import os
import re
import subprocess
import urllib.parse

if __package__:
    from .control_plane import Rejected, parse_json, read_text, require, safe_path
else:
    from control_plane import Rejected, parse_json, read_text, require, safe_path


WORKFLOW = ".github/workflows/control-plane.yml"
SHA = r"(?:[0-9a-f]{40}|[0-9a-f]{64})"


def environment():
    """排除继承的仓库、配置、替换对象与 GH 主机路由，令命令只读且不交互。"""
    result = {key: value for key, value in os.environ.items()
              if not key.startswith("GIT_") and key not in ("GH_HOST", "GH_REPO", "GH_DEBUG")}
    result.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
                  GIT_NO_REPLACE_OBJECTS="1", GIT_OPTIONAL_LOCKS="0", GIT_TERMINAL_PROMPT="0",
                  GH_PROMPT_DISABLED="1", GH_PAGER="cat")
    return result


def command(argv, root):
    """有界读取；不回显可能含凭据的命令、原始响应或失败诊断。"""
    try:
        result = subprocess.run(argv, cwd=root, env=environment(), capture_output=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise Rejected("CP_DELIVERY_UNAVAILABLE", "只读 Git／GitHub 查询不可用或超时") from error
    require(result.returncode == 0, "CP_DELIVERY_UNAVAILABLE", "只读 Git／GitHub 查询失败")
    return result.stdout.decode("utf-8")


def git(root, *args):
    """不更新索引、引用或工作区，不调用用户配置的 fsmonitor。"""
    return command(["git", "-C", str(root), "-c", "core.fsmonitor=false", *args], root)


class GitHub:
    """固定 github.com 的 GET API；路径由验证过的对象构造，不接受报告或替代服务。"""

    def __init__(self, root, repository):
        self.root, self.repository = root, repository

    def get(self, path):
        raw = command(["gh", "api", "--hostname", "github.com", "--method", "GET",
                       "-H", "Accept: application/vnd.github+json",
                       "-H", "X-GitHub-Api-Version: 2026-03-10",
                       f"repos/{self.repository}/{path}"], self.root)
        try:
            return parse_json(raw, "GitHub API")
        except Rejected as error:
            raise Rejected("CP_DELIVERY_SCHEMA", "GitHub 返回非法或歧义 JSON") from error


def clean_head(root):
    """逐个固定树对象核对工作区字节及模式，不能由 assume-unchanged 隐藏改动。"""
    head = git(root, "rev-parse", "HEAD").strip()
    require(not git(root, "status", "--porcelain=v1", "--untracked-files=all"),
            "CP_DELIVERY_DIRTY", "交付候选含未提交输入，不能用 HEAD 代表当前工作区")
    for entry in git(root, "ls-tree", "-rz", "--full-tree", head).split("\0"):
        if not entry:
            continue
        metadata, path = entry.split("\t", 1)
        mode, kind, digest = metadata.split()
        target = safe_path(root, path)
        require(kind == "blob" and mode in ("100644", "100755") and target.is_file(),
                "CP_DELIVERY_DIRTY", "交付树包含未支持类型或缺失文件")
        data = target.read_bytes()
        actual = hashlib.new("sha1" if len(digest) == 40 else "sha256",
                             f"blob {len(data)}\0".encode() + data).hexdigest()
        require(actual == digest and bool(target.stat().st_mode & 0o111) == (mode == "100755"),
                "CP_DELIVERY_DIRTY", "实际文件字节／模式与提交树不符")
    return head


def repository(root):
    """只支持已使用的 GitHub HTTPS／SSH origin；未知托管方式显式拒绝。"""
    value = git(root, "remote", "get-url", "origin").strip()
    match = re.fullmatch(r"(?:https://github\.com/|git@github\.com:)([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?", value)
    require(match is not None and all(part not in (".", "..") for part in match[1].split("/")),
            "CP_DELIVERY_PROVIDER", "origin 不是受支持的无内嵌凭据 GitHub 地址")
    return match[1]


def condition(expression, group, calibration):
    """只解释当前受支持工作流的条件；新增语法须补适配，不能默认视为已执行。"""
    if expression in (None, "success()"):
        return True
    if expression == "needs.scope-checks.outputs.maintenance-required == 'true'":
        return calibration
    terms = expression.split(" || ")
    matches = [re.fullmatch(r"matrix\.group == '([a-z-]+)'", term) for term in terms]
    require(all(matches), "CP_DELIVERY_WORKFLOW", "未支持的工作流条件")
    return any(match[1] == group for match in matches)


def workflow_contract(text, calibration):
    """从固定候选工作流读取 job／命名 step；限定当前缩进和单轴矩阵，拒绝歧义。"""
    require("continue-on-error:" not in text, "CP_DELIVERY_WORKFLOW", "交付核对不支持忽略失败")
    require(text.count("\njobs:\n") == 1, "CP_DELIVERY_WORKFLOW", "须有唯一普通 jobs 对象")
    jobs, current, step, matrix = {}, None, None, False
    for line in text.split("\njobs:\n", 1)[-1].splitlines():
        match = re.fullmatch(r"  ([a-z][a-z-]*):", line)
        if match:
            current = {"steps": [], "groups": None, "condition": None}
            require(match[1] not in jobs, "CP_DELIVERY_WORKFLOW", "重复 job")
            jobs[match[1]], step = current, None
        elif current is not None:
            if matrix and line.strip() and len(line) - len(line.lstrip()) <= 6:
                matrix = False
            if matrix and line.strip():
                require(re.fullmatch(r"        group: \[[a-z-]+(?:, [a-z-]+)*\]", line),
                        "CP_DELIVERY_WORKFLOW", "仅支持显式 group 单轴矩阵，不支持排除／包含或额外轴")
            if line.startswith("    name: "):
                require("name" not in current, "CP_DELIVERY_WORKFLOW", "重复 job 名称")
                current["name"] = line[10:]
            elif line.startswith("    if: "):
                require(current["condition"] is None, "CP_DELIVERY_WORKFLOW", "重复 job 条件")
                current["condition"] = line[8:]
            elif line.startswith("    needs: "):
                require("needs" not in current, "CP_DELIVERY_WORKFLOW", "重复 job 依赖")
                current["needs"] = line[11:]
            elif line == "      matrix:":
                matrix = True
            elif line.startswith("        group: ["):
                require(current["groups"] is None, "CP_DELIVERY_WORKFLOW", "重复矩阵")
                current["groups"] = line[16:-1].split(", ")
            elif line.startswith("      - "):
                require(line.startswith(("      - name: ", "      - uses: ")),
                        "CP_DELIVERY_WORKFLOW", "执行步骤须具名，不支持隐式名称的 run")
                step = {"name": line[14:], "condition": None} if line.startswith("      - name: ") else None
                if step is not None:
                    current["steps"].append(step)
            elif line.startswith("        if: ") and step is not None:
                require(step["condition"] is None, "CP_DELIVERY_WORKFLOW", "重复 step 条件")
                step["condition"] = line[12:]
    require(set(jobs) == {"scope-checks", "advisory-data", "mechanism-checks"},
            "CP_DELIVERY_WORKFLOW", "工作流 job 结构不在当前支持范围")
    expected = {}
    for identifier, job in jobs.items():
        require(job.get("needs") == {"scope-checks": None, "advisory-data": "scope-checks",
                                     "mechanism-checks": "advisory-data"}[identifier],
                "CP_DELIVERY_WORKFLOW", "未支持的 job 依赖")
        require(job["condition"] == ("needs.scope-checks.outputs.maintenance-required == 'true'"
                                     if identifier == "advisory-data" else None),
                "CP_DELIVERY_WORKFLOW", "job 条件偏离当前校准选择合同")
        require((job["groups"] is not None) == (identifier == "mechanism-checks"),
                "CP_DELIVERY_WORKFLOW", "矩阵位置不在支持范围")
        require(job.get("name") and job["steps"], "CP_DELIVERY_WORKFLOW", "job 缺少名称或实际步骤")
        for group in job["groups"] or [None]:
            name = job["name"].replace("${{ matrix.group }}", group or "")
            require("${{" not in name, "CP_DELIVERY_WORKFLOW", "不支持动态 job 名称")
            require(name not in expected, "CP_DELIVERY_WORKFLOW", "重复 job 名称")
            steps = {item["name"]: condition(item["condition"], group, calibration) for item in job["steps"]}
            require(len(steps) == len(job["steps"]), "CP_DELIVERY_WORKFLOW", "重复 step 名称")
            required = condition(job["condition"], group, calibration)
            expected[name] = {"required": required and (calibration or identifier == "scope-checks"), "steps": steps}
    return expected


def verify_jobs(api, run, expected):
    """读取该次 attempt 的全部分页，完整 job／step 的成功不能由总绿灯替代。"""
    jobs, total = [], None
    for page in range(1, 101):
        data = api.get(f"actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs?per_page=100&page={page}")
        require(isinstance(data.get("total_count"), int) and isinstance(data.get("jobs"), list),
                "CP_DELIVERY_CI_COVERAGE", "CI job 分页缺少总数或内容")
        total = data["total_count"] if total is None else total
        require(total == data["total_count"] and total <= 10000, "CP_DELIVERY_CI_CHANGED", "CI job 总数变化或超出支持范围")
        jobs.extend(data["jobs"])
        if len(jobs) >= total:
            break
        require(data["jobs"], "CP_DELIVERY_CI_COVERAGE", "CI 分页为空但仍有缺失 job")
    require(len(jobs) == total and len({item["id"] for item in jobs}) == len(jobs),
            "CP_DELIVERY_CI_COVERAGE", "CI job 数量缺失或重复")
    names = [item["name"] for item in jobs]
    require(len(names) == len(set(names)) and
            {name for name, value in expected.items() if value["required"]} <= set(names),
            "CP_DELIVERY_CI_COVERAGE", "必需 CI job 缺失或名称重复")
    for job in jobs:
        require(job.get("run_id") == run["id"] and job.get("head_sha") == run["head_sha"],
                "CP_DELIVERY_CI_INPUT", "CI job 不属于该次运行／提交")
        spec = expected.get(job["name"])
        # GitHub 在 job-level if 为 false 时可能不展开矩阵，只返回空 group 的跳过对象。
        skipped_matrix = job["name"] == "隔离门禁校准 / （非整套验收）" and not any(
            value["required"] for name, value in expected.items() if name.startswith("隔离门禁校准 / "))
        require(spec is not None or skipped_matrix, "CP_DELIVERY_CI_COVERAGE", "出现未识别 CI job")
        if skipped_matrix or not spec["required"]:
            require(job.get("conclusion") == "skipped", "CP_DELIVERY_CI_COVERAGE", "不适用 job 状态与实际变更范围不符")
            continue
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                "CP_DELIVERY_CI_FAILED", "必需 CI job 未完成、失败或跳过")
        steps = job.get("steps", [])
        mapped = {item["name"]: item for item in steps}
        require(steps and len(mapped) == len(steps) and set(spec["steps"]) <= set(mapped),
                "CP_DELIVERY_CI_COVERAGE", "必需 CI step 缺失、为空或重复")
        for name, item in mapped.items():
            wanted = "success" if spec["steps"].get(name, True) else "skipped"
            require(item.get("status") == "completed" and item.get("conclusion") == wanted,
                    "CP_DELIVERY_CI_FAILED", "CI step 失败／跳过或适用条件不符")
    return jobs


def verify_run(api, root, run_id, sha, branch, calibration):
    """核对固定工作流及最新 attempt，调用末尾再次读取以识别重跑／状态漂移。"""
    require(type(run_id) is int and run_id > 0, "CP_DELIVERY_QUERY", "须提供真实正整数 CI run ID")
    run = api.get(f"actions/runs/{run_id}")
    require(run.get("id") == run_id and run.get("head_sha") == sha and run.get("head_branch") == branch and
            run.get("repository", {}).get("full_name") == api.repository and
            run.get("head_repository", {}).get("full_name") == api.repository and
            run.get("path") == WORKFLOW and run.get("event") in ("push", "workflow_dispatch"),
            "CP_DELIVERY_CI_INPUT", "CI 仓库、分支、提交、事件或工作流不匹配")
    require(type(run.get("run_attempt")) is int and run["run_attempt"] > 0 and
            run.get("status") == "completed" and run.get("conclusion") == "success",
            "CP_DELIVERY_CI_FAILED", "CI 未完成、失败或 attempt 无效")
    expected = workflow_contract(git(root, "show", f"{sha}:{WORKFLOW}"), calibration or run["event"] == "workflow_dispatch")
    jobs = verify_jobs(api, run, expected)
    return run, jobs


def verify_record(plane, identifier, functional, head):
    """收尾只允许精确功能提交的一个普通 doc: 子提交及验收字段／派生索引变化。"""
    require(git(plane.root, "show", "-s", "--format=%P", head).strip().split() == [functional],
            "CP_DELIVERY_RECORD", "记录提交须是功能提交的唯一直接子提交")
    require(re.match(r"doc:\s*[\u4e00-\u9fff]", git(plane.root, "show", "-s", "--format=%s", head)),
            "CP_DELIVERY_RECORD", "记录提交须使用中文 doc: 前缀")
    task_path = plane.documents[plane.catalog["tasks"][identifier]]["path"]
    changed = set(git(plane.root, "diff", "--name-only", "--no-renames", "-z", functional, head, "--").split("\0")) - {""}
    indices = plane.indices()
    require(task_path in changed and changed <= {task_path, *indices},
            "CP_DELIVERY_RECORD", "记录提交包含非验收记录／索引变更")
    pattern = r"^```control-plane-task\s*\n(.*?)^```\s*$"
    old = git(plane.root, "show", f"{functional}:{task_path}")
    new = read_text(plane.root / task_path)
    before, after = re.findall(pattern, old, re.M | re.S), re.findall(pattern, new, re.M | re.S)
    require(len(before) == len(after) == 1 and re.sub(pattern, "", old, flags=re.M | re.S) ==
            re.sub(pattern, "", new, flags=re.M | re.S), "CP_DELIVERY_RECORD", "记录提交夹带任务正文变化")
    original, updated = parse_json(before[0], task_path), parse_json(after[0], task_path)
    allowed = {"acceptance", "closing", "frontier", "pending"}
    require(original.get("acceptance") is None and
            {k: v for k, v in original.items() if k not in allowed} ==
            {k: v for k, v in updated.items() if k not in allowed},
            "CP_DELIVERY_RECORD", "验收记录例外被重复使用或夹带合同／任务范围变化")
    for path in changed:
        require(git(plane.root, "ls-tree", functional, "--", path).startswith("100644 ") and
                git(plane.root, "ls-tree", head, "--", path).startswith("100644 "),
                "CP_DELIVERY_RECORD", "记录提交包含新增、删除或文件模式变化")
        if path != task_path:
            require(read_text(plane.root / path) == indices[path], "CP_DELIVERY_RECORD", "索引不是当前事实的机械派生")


def inspect_delivery(plane, identifier, query_path=None, api_factory=GitHub):
    """生产入口只接受对象选择；测试可注入协议响应，结果始终不产生人工验收。"""
    result = {"ok": False, "proof_scope": "git-ci-object-checks-only", "human_acceptance": False,
              "delivery_complete": False, "task": identifier, "diagnostics": [],
              "human_review": "required; source authenticity and acceptance semantics are not machine-proven"}
    try:
        require(query_path is not None, "CP_DELIVERY_REQUIRED", "缺少精确候选及真实 CI 对象；本地校准不能替代")
        require(query_path.is_absolute() and not query_path.resolve().is_relative_to(plane.root),
                "CP_UNSAFE_PATH", "交付对象查询使用仓库外绝对路径，不接受仓库内自报成功")
        raw = read_text(query_path)
        query = parse_json(raw, "delivery query")
        require(set(query) <= {"task", "functional_sha", "branch", "functional_run_id", "record_run_id"} and
                query.get("task") == identifier and re.fullmatch(SHA, query.get("functional_sha", "")) and
                isinstance(query.get("branch"), str) and re.fullmatch(r"[A-Za-z0-9_./-]+", query["branch"]),
                "CP_DELIVERY_QUERY", "查询须绑定任务、完整 SHA、分支及 CI run ID；不接受通过声明")
        root, task = plane.root, plane.tasks[identifier]
        head = clean_head(root)
        require(git(root, "rev-parse", "--show-toplevel").strip() == str(root), "CP_DELIVERY_GIT", "不是查询仓库根")
        functional = query["functional_sha"]
        require(git(root, "cat-file", "-t", functional).strip() == "commit" and
                git(root, "merge-base", task["base"], functional).strip() == task["base"],
                "CP_DELIVERY_GIT", "功能对象不是任务起点之后的真实提交")
        accepted = task["acceptance"]
        if accepted:
            require(accepted["functional_sha"] == functional, "CP_DELIVERY_ACCEPTANCE", "人工记录不属于当前功能对象")
            verify_record(plane, identifier, functional, head)
            # 只固定现有唯一来源的字节，不把自填 actor／链接当成真实人类决定。
            source = plane.reference(accepted["source"], historical=True).split("#")[0]
            result["acceptance_record"] = {"functional_sha": functional, "source": accepted["source"],
                                           "source_sha256": hashlib.sha256((root / source).read_bytes()).hexdigest(),
                                           "proof_scope": "source-association-only"}
        else:
            require(head == functional and "record_run_id" not in query,
                    "CP_DELIVERY_GIT", "待验收功能对象须为当前 HEAD，不接受无验收的记录提交")
        repo = repository(root)
        api = api_factory(root, repo)
        branch_path = "git/ref/heads/" + urllib.parse.quote(query["branch"], safe="")
        remote = api.get(branch_path)
        require(remote.get("ref") == "refs/heads/" + query["branch"] and
                remote.get("object", {}).get("type") == "commit" and remote["object"].get("sha") == head,
                "CP_DELIVERY_REMOTE", "真实远端分支不属于当前候选／记录提交")
        calibration = plane.maintenance_changed(
            git(root, "diff", "--name-only", "--no-renames", "-z", task["base"], functional, "--").split("\0"),
            task["base"], read_git=git)
        observed = [verify_run(api, root, query.get("functional_run_id"), functional, query["branch"], calibration)]
        if accepted:
            observed.append(verify_run(api, root, query.get("record_run_id"), head, query["branch"], False))
        for run, jobs in observed:
            require(api.get(f"actions/runs/{run['id']}") == run, "CP_DELIVERY_CI_CHANGED", "核对期间 CI 被重跑或状态变化")
            require(verify_jobs(api, run, workflow_contract(git(root, "show", f"{run['head_sha']}:{WORKFLOW}"),
                    (calibration if run["head_sha"] == functional else False) or run["event"] == "workflow_dispatch")) == jobs,
                    "CP_DELIVERY_CI_CHANGED", "核对期间 CI job／step 变化")
        require(api.get(branch_path) == remote and repository(root) == repo and
                clean_head(root) == head and
                read_text(query_path) == raw, "CP_DELIVERY_CHANGED", "核对期间 Git、远端或查询输入变化")
        result.update(ok=True, repository=repo, functional_sha=functional, observed_head=head,
                      remote_sha=remote["object"]["sha"], technical_status="closing-checked" if accepted else "candidate-checked",
                      runs=[{"id": run["id"], "attempt": run["run_attempt"], "sha": run["head_sha"],
                             "jobs": len(jobs), "url": f"https://github.com/{repo}/actions/runs/{run['id']}"} for run, jobs in observed])
    except Rejected as error:
        result["diagnostics"].append(error.diagnostic)
    except (KeyError, TypeError, ValueError, AttributeError, UnicodeError, OSError):
        result["diagnostics"].append({"code": "CP_DELIVERY_SCHEMA", "message": "Git／CI 对象或查询格式不符合支持合同"})
    return result
