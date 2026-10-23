#!/usr/bin/env python3
"""检查仓库 Markdown 的相对链接：目标文件存在，锚点对应真实标题（GitHub 规则）。"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP = {".git", "target", "node_modules", ".idea", ".p1-runner"}
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)\)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")


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


def main() -> int:
    problems, cache = [], {}
    files = list(markdown_files())
    for source in files:
        fence = False
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            if line.lstrip().startswith("```"):
                fence = not fence
            if fence:
                continue
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
    for problem in problems:
        print(problem)
    print(f"检查 {len(files)} 个 Markdown 文件，问题 {len(problems)} 个")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
