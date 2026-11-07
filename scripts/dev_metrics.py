#!/usr/bin/env python3
"""按里程碑统计研发度量与北极星指标，输出 Markdown 报告。

用法：scripts/dev_metrics.py [--milestone 首版] [--runs 10] [-o 报告路径]
数据全部来自 GitHub（gh）和仓库本身：里程碑内的任务 issue 及关闭它们的已合并 PR、CI 运行、规则卡、
domain-forge-build-rules 的反例测试（检出结果取自本机 surefire 报告，没有则标“未运行”）。
指标口径见 docs/platform/质量模型与北极星.md。
"""

import argparse
import json
import re
import statistics
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import date, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULES_DIR = ROOT / "platform/domain-forge-build-rules"
RULES_TEST = RULES_DIR / "src/test/java/org/domainforge/buildrules/ArchitectureRulesTest.java"
SUREFIRE = RULES_DIR / "target/surefire-reports/TEST-org.domainforge.buildrules.ArchitectureRulesTest.xml"
CARD_DIRS = ("docs/rules", "docs/workflow")
CARD_HEADING = re.compile(r"^### ([A-Z]-\d{2}) \S")
CHECK_LINE = re.compile(r"^\*\*检查\*\*：(.+)$")


def run(*cmd):
    return subprocess.run(cmd, cwd=ROOT, check=True, capture_output=True, text=True).stdout


def gh_json(*args):
    return json.loads(run("gh", *args))


def parse_time(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def hours(delta):
    return delta.total_seconds() / 3600


def fmt_hours(value):
    return f"{value:.1f} 小时" if value < 48 else f"{value / 24:.1f} 天"


def median(values):
    return statistics.median(values) if values else None


def milestone_issues(milestone):
    return gh_json("issue", "list", "--milestone", milestone, "--state", "all", "--limit", "500",
                   "--json", "number,title,state,labels,createdAt,closedAt")


def merged_prs():
    return gh_json("pr", "list", "--state", "merged", "--limit", "500", "--json",
                   "number,title,createdAt,mergedAt,labels,reviews,comments,files,closingIssuesReferences")


def pr_issue_numbers(pr):
    refs = {ref["number"] for ref in pr["closingIssuesReferences"]}
    refs.update(int(n) for n in re.findall(r"\(#(\d+)\)", pr["title"]))
    return refs


def review_rounds(pr):
    """评审轮次 = 1 + 要求修改的评审次数。"""
    return 1 + sum(1 for review in pr["reviews"] if review["state"] == "CHANGES_REQUESTED")


def review_comments(pr):
    return len(pr["comments"]) + sum(1 for review in pr["reviews"] if review["body"].strip())


def maven_modules():
    dirs = [Path(p).parent.as_posix() for p in run("git", "ls-files", "*pom.xml").split()]
    return sorted((d for d in dirs if d not in (".", "")), key=len, reverse=True)


def modules_of(pr, modules):
    touched = set()
    for item in pr["files"]:
        for module in modules:
            if item["path"].startswith(module + "/"):
                touched.add(module)
                break
    return touched


def pr_table(prs, modules):
    rows = ["| PR | 标题 | 创建→合并 | 评审轮次 | 评审意见 | 返工 | 模块数 |", "|---|---|---|---|---|---|---|"]
    for pr in prs:
        cycle = parse_time(pr["mergedAt"]) - parse_time(pr["createdAt"])
        title = pr["title"].replace("|", "\\|")
        rework = "是" if any(label["name"] == "rework" for label in pr["labels"]) else "否"
        rows.append(f"| #{pr['number']} | {title} | {fmt_hours(hours(cycle))} | {review_rounds(pr)} "
                    f"| {review_comments(pr)} | {rework} | {len(modules_of(pr, modules))} |")
    return rows


def north_star(prs, modules):
    total = len(prs)
    first_pass = sum(1 for pr in prs
                     if not any(label["name"] == "rework" for label in pr["labels"]) and review_rounds(pr) == 1)
    cycles = [hours(parse_time(pr["mergedAt"]) - parse_time(pr["createdAt"])) for pr in prs]
    distribution = Counter(len(modules_of(pr, modules)) for pr in prs)
    lines = [
        f"- AI 一次通过率：{first_pass}/{total}（{first_pass * 100 // total if total else 0}%）",
        f"- 中位交付周期（PR 创建到合并）：{fmt_hours(median(cycles)) if cycles else '无数据'}",
        "- 变更局部性（每个 PR 涉及的 Maven 模块数 → PR 数）：" + (
            "、".join(f"{k} 个模块 {v} 个" for k, v in sorted(distribution.items())) or "无数据"),
    ]
    return lines


def rule_card_stats():
    total = auto = 0
    for directory in CARD_DIRS:
        for path in sorted((ROOT / directory).glob("*.md")):
            in_card = False
            for line in path.read_text(encoding="utf-8").splitlines():
                if CARD_HEADING.match(line):
                    in_card = True
                    total += 1
                    continue
                match = CHECK_LINE.match(line)
                if in_card and match:
                    auto += "自动" in match.group(1)
                    in_card = False
    return auto, total


def counterexamples():
    """解析反例测试：每个调用 assertViolation 的测试是一个反例，按规则标签汇总。"""
    text = RULES_TEST.read_text(encoding="utf-8")
    cases = re.findall(r"void (\w+)\(\)\s*\{\s*assertViolation\(\"[^\"]+\",\s*\"(\[[^\"]+\])\"\)", text)
    results = {}
    if SUREFIRE.exists():
        for case in ET.parse(SUREFIRE).getroot().iter("testcase"):
            failed = case.find("failure") is not None or case.find("error") is not None
            results[case.get("name").rstrip("()")] = not failed
    by_tag = {}
    for name, tag in cases:
        count, detected = by_tag.get(tag, (0, 0))
        by_tag[tag] = (count + 1, detected + (1 if results.get(name) else 0))
    return by_tag, bool(results)


def counterexample_section():
    by_tag, ran = counterexamples()
    lines = ["| 规则标签 | 反例数 | 本次构建检出 |", "|---|---|---|"]
    for tag, (count, detected) in sorted(by_tag.items()):
        lines.append(f"| {tag} | {count} | {f'{detected}/{count}' if ran else '未运行'} |")
    total = sum(count for count, _ in by_tag.values())
    detected = sum(d for _, d in by_tag.values())
    lines.append(f"| 合计 | {total} | {f'{detected}/{total}' if ran else '未运行'} |")
    note = "" if ran else ("\n\n未找到 surefire 报告，检出结果未统计；先运行 "
                           "`platform/mvnw -f platform/pom.xml -pl domain-forge-build-rules test` 再生成报告。")
    return "\n".join(lines) + note


def ci_section(runs):
    listed = gh_json("run", "list", "--branch", "main", "--workflow", "ci.yml", "--status", "success",
                     "--limit", str(runs), "--json", "databaseId,createdAt,updatedAt")
    durations = {}
    walls = []
    for item in listed:
        info = gh_json("run", "view", str(item["databaseId"]), "--json", "jobs")
        walls.append(hours(parse_time(item["updatedAt"]) - parse_time(item["createdAt"])) * 60)
        for job in info["jobs"]:
            if job["conclusion"] == "success" and job.get("startedAt") and job.get("completedAt"):
                span = parse_time(job["completedAt"]) - parse_time(job["startedAt"])
                durations.setdefault(job["name"], []).append(span.total_seconds() / 60)
    lines = [f"取 main 上最近 {len(listed)} 次成功的 CI 运行（分钟，中位数／最大值）。", "",
             "| 作业 | 中位 | 最大 |", "|---|---|---|"]
    for name, values in sorted(durations.items()):
        lines.append(f"| {name} | {median(values):.1f} | {max(values):.1f} |")
    if walls:
        lines.append(f"| 整次运行（墙钟） | {median(walls):.1f} | {max(walls):.1f} |")
    return "\n".join(lines)


def escaped_section(milestone, issues):
    escaped = gh_json("issue", "list", "--label", "escaped-defect", "--state", "all", "--limit", "200",
                      "--json", "number,title,state")
    in_milestone = sum(1 for issue in issues if any(label["name"] == "escaped-defect" for label in issue["labels"]))
    lines = [f"- 全部带 `escaped-defect` 的 issue：{len(escaped)} 个；其中属于里程碑「{milestone}」：{in_milestone} 个"]
    lines += [f"  - #{item['number']} {item['title']}（{item['state']}）" for item in escaped]
    return "\n".join(lines)


def build_report(milestone, runs):
    issues = milestone_issues(milestone)
    numbers = {issue["number"] for issue in issues}
    closed = sum(1 for issue in issues if issue["state"] == "CLOSED")
    prs = sorted((pr for pr in merged_prs() if pr_issue_numbers(pr) & numbers), key=lambda pr: pr["number"])
    modules = maven_modules()
    auto, total = rule_card_stats()
    head = run("git", "rev-parse", "--short", "HEAD").strip()
    rework = sum(1 for pr in prs if any(label["name"] == "rework" for label in pr["labels"]))
    sections = [
        f"# 研发度量报告：里程碑「{milestone}」",
        f"生成日期 {date.today()}，仓库提交 `{head}`，由 `scripts/dev_metrics.py` 从 GitHub 生成。"
        f"里程碑任务 issue {len(issues)} 个（已关闭 {closed} 个）；统计范围是关闭这些 issue 的已合并 PR，共 {len(prs)} 个。",
        "## 统计口径\n\n"
        "- PR 与任务的关系：PR 的 `closingIssuesReferences`，或标题末尾的 `(#任务号)`。\n"
        "- 评审轮次 = 1 + “要求修改”评审次数；评审意见数 = PR 对话评论数 + 带正文的评审数。"
        "本仓库由同一账号开 PR、自动合并，评审多发生在 PR 外，因此这两项偏低。\n"
        "- AI 一次通过率 = 没有 `rework` 标签且评审轮次为 1 的 PR 占比。"
        "历史 PR 没有统一打 `rework`、`escaped-defect` 标签，这两项在首份报告中偏低，随着标签纪律建立才有参考价值。\n"
        "- 交付周期取 PR 创建到合并；任务开工时间 GitHub 上没有记录，不另算。\n"
        "- 模块数 = PR 改动文件所属的 Maven 模块去重个数；只改文档、脚本、CI 的 PR 为 0。",
        "## 北极星指标\n\n### 价值\n\n" + "\n".join(north_star(prs, modules)[:2]) +
        f"\n\n### 质量\n\n- 原则可执行率：{auto}/{total}（{auto * 100 // total}%，规则卡检查行含“自动”标签的比例）\n"
        + north_star(prs, modules)[2] +
        f"\n\n### 护栏\n\n{escaped_section(milestone, issues)}\n"
        f"- 返工 PR（`rework`）：{rework}/{len(prs)}\n\n### 领先指标：CI 作业耗时\n\n{ci_section(runs)}",
        "## 架构规则的反例与检出\n\n" + counterexample_section(),
        "## 里程碑 PR 明细\n\n" + "\n".join(pr_table(prs, modules)),
    ]
    return "\n\n".join(sections) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--milestone", default="首版")
    parser.add_argument("--runs", type=int, default=10, help="统计 CI 耗时的运行次数")
    parser.add_argument("-o", "--output", help="输出文件；省略则打印到标准输出")
    args = parser.parse_args()
    report = build_report(args.milestone, args.runs)
    if args.output:
        Path(args.output).write_text(report, encoding="utf-8")
    else:
        sys.stdout.write(report)


if __name__ == "__main__":
    main()
