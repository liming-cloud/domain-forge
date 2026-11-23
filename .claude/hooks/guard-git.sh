#!/usr/bin/env bash
# PreToolUse 钩子（#136）：拦截在 main/master 上 git commit，以及 git push --force/-f/--force-with-lease。
# 输入是 Claude Code 传来的 JSON（tool_input.command、cwd）；退出码 2 表示拒绝，stderr 转给 Claude。
# 命令先去掉 heredoc 正文，再按 shell 词法拆成简单命令，只看命令词是 git 的调用；
# 分支取 cd／git -C 的目标目录，解析不了（含变量、目录不存在）时退回会话 cwd（#180）。
set -euo pipefail

input=$(cat)
exec python3 -c '
import json, os, re, shlex, subprocess, sys

data = json.loads(sys.argv[1])
command = data.get("tool_input", {}).get("command", "")
session_cwd = data.get("cwd") or "."


def deny(message):
    print(message, file=sys.stderr)
    sys.exit(2)


def without_heredoc_bodies(text):
    kept, ends = [], []
    for line in text.split("\n"):
        if ends:
            if line.strip() == ends[0]:
                ends.pop(0)
            continue
        kept.append(line)
        ends = re.findall(r"(?<!<)<<(?!<)-?\s*[\"\x27]?([A-Za-z_][A-Za-z0-9_]*)[\"\x27]?", line)
    return "\n".join(kept)


def simple_commands(text):
    text = text.replace("\\\n", " ").replace("\n", ";")
    try:
        lexer = shlex.shlex(text, posix=True, punctuation_chars=True)
        lexer.whitespace_split = True
        lexer.commenters = ""
        tokens = list(lexer)
    except ValueError:
        tokens = re.sub(r"([;&|()]+)", r" \1 ", text).split()
    words = []
    for token in tokens:
        if set(token) <= set(";&|()"):
            yield words
            words = []
        else:
            words.append(token)
    yield words


def resolve(base, target):
    if "$" in target or "`" in target:
        return session_cwd
    path = os.path.join(base, os.path.expanduser(target))
    return path if os.path.isdir(path) else session_cwd


def branch_of(directory):
    result = subprocess.run(["git", "-C", directory, "branch", "--show-current"], capture_output=True, text=True)
    return result.stdout.strip()


def check_git(args, cwd):
    while args and args[0].startswith("-"):
        if args[0] == "-C" and len(args) > 1:
            cwd = resolve(cwd, args[1])
        args = args[2:] if args[0] in ("-C", "-c") else args[1:]
    if args[:1] == ["push"] and any(a in ("-f", "--force", "--force-with-lease") or a.startswith("--force-with-lease=") for a in args):
        deny("不允许强推（git push --force/-f）：冲突在本地解决后普通推送。")
    if args[:1] == ["commit"]:
        branch = branch_of(cwd)
        if branch in ("main", "master"):
            deny(f"不允许在 {branch} 上提交：先从最新 main 新建功能分支（见 AGENTS.md“开工前”）。")


cwd = session_cwd
for words in simple_commands(without_heredoc_bodies(command)):
    while words and re.match(r"[A-Za-z_][A-Za-z0-9_]*=", words[0]):
        words = words[1:]
    if words[:1] == ["cd"]:
        cwd = resolve(cwd, words[1] if len(words) > 1 else "~")
    elif words[:1] == ["git"]:
        check_git(words[1:], cwd)
' "$input"
