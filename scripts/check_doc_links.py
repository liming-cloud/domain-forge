#!/usr/bin/env python3
"""检查仓库 Markdown 的相对链接与规则卡。

链接：目标文件存在，锚点对应真实标题（GitHub 规则）。
规则卡（docs/rules、docs/workflow 中 `### X-nn 标题` 形式的卡片）：卡号唯一；仓库中所有卡号引用都指向存在的卡；
规则卡索引覆盖全部卡；每张卡恰好有一行检查，检查标签合法。
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP = {".git", "target", "node_modules", ".idea", ".p1-runner"}
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)\)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
CARD_DIRS = ("docs/rules", "docs/workflow")
INDEX = ROOT / "docs/rules/00-索引.md"
CARD_HEADING = re.compile(r"^### ([A-Z]-\d{2}) \S")
CARD_REF = re.compile(r"(?<![A-Za-z0-9_-])[RSBCPHAOFTW]-\d{2}(?![\d-])")
CHECK_LINE = re.compile(r"^\*\*检查\*\*：(.+)$")
CHECK_LABEL = re.compile(r"^(自动\[[^\]]+\]|测试|评审)$")


def slug(text: str) -> str:
    text = re.sub(r"`|\*\*|__", "", text.strip().lower())
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = "".join(ch for ch in text if ch.isalnum() or ch in " -_")
    return text.replace(" ", "-")


def anchors(path: Path) -> set:
    found, seen, fence = set(), {}, False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("```"):
            fence = not fence
            continue
        match = None if fence else HEADING.match(line)
        if match:
            base = slug(match.group(2))
            count = seen.get(base, 0)
            seen[base] = count + 1
            found.add(base if count == 0 else f"{base}-{count}")
    return found


def markdown_files():
    for path in ROOT.rglob("*.md"):
        if not SKIP.intersection(path.relative_to(ROOT).parts):
            yield path


def prose_lines(path: Path):
    """逐行给出代码块之外的内容。"""
    fence = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("```"):
            fence = not fence
            continue
        if not fence:
            yield number, line


def check_links(files, problems):
    cache = {}
    for source in files:
        for number, line in prose_lines(source):
            for target in LINK.findall(line):
                if re.match(r"^[a-z]+:", target):
                    continue
                file_part, _, anchor = target.partition("#")
                path = (source.parent / file_part).resolve() if file_part else source
                where = f"{source.relative_to(ROOT)}:{number}"
                if not path.exists():
                    problems.append(f"{where} 链接目标不存在：{target}")
                elif anchor and path.suffix == ".md":
                    names = cache.setdefault(path, anchors(path))
                    if anchor.lower() not in names:
                        problems.append(f"{where} 锚点不存在：{target}")


def check_labels(text, where, problems):
    for segment in text.rstrip("。").split("；"):
        for label in segment.split("：", 1)[0].split("、"):
            if not CHECK_LABEL.match(label.strip()):
                problems.append(f"{where} 检查标签不合法：{label.strip()}（只能是 自动[名称]、测试、评审）")


def collect_cards(problems) -> set:
    cards, current, checks = {}, None, 0

    def close():
        if current and checks != 1:
            problems.append(f"{current} 卡片应恰好有一行检查，实际 {checks} 行")

    for directory in CARD_DIRS:
        for path in sorted((ROOT / directory).glob("*.md")):
            if path == INDEX:
                continue
            for number, line in prose_lines(path):
                where = f"{path.relative_to(ROOT)}:{number}"
                card = CARD_HEADING.match(line)
                if card or line.startswith("#"):
                    close()
                    current, checks = None, 0
                if card:
                    if card.group(1) in cards:
                        problems.append(f"{where} 卡号重复：{card.group(1)}（首次出现在 {cards[card.group(1)]}）")
                    cards.setdefault(card.group(1), where)
                    current = where
                check = CHECK_LINE.match(line)
                if check and current:
                    checks += 1
                    check_labels(check.group(1), where, problems)
            close()
            current, checks = None, 0
    return set(cards)


def tracked_text_files():
    names = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z"], capture_output=True, check=True).stdout
    for name in names.decode("utf-8").split("\0"):
        path = ROOT / name
        if name and path.is_file():
            try:
                yield path, path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue


def check_cards(problems):
    cards = collect_cards(problems)
    for path, text in tracked_text_files():
        for number, line in enumerate(text.splitlines(), 1):
            for ref in CARD_REF.findall(line):
                if ref not in cards:
                    problems.append(f"{path.relative_to(ROOT)}:{number} 引用了不存在的规则卡：{ref}")
    indexed = {ref for _, line in prose_lines(INDEX) for ref in re.findall(r"\[([A-Z]-\d{2})\]\(", line)}
    for missing in sorted(cards - indexed):
        problems.append(f"{INDEX.relative_to(ROOT)} 索引未收录规则卡：{missing}")
    return len(cards)


def main() -> int:
    problems = []
    files = list(markdown_files())
    check_links(files, problems)
    count = check_cards(problems)
    for problem in problems:
        print(problem)
    print(f"检查 {len(files)} 个 Markdown 文件、{count} 张规则卡，问题 {len(problems)} 个")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
