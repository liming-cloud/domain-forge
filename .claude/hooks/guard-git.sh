#!/usr/bin/env bash
# PreToolUse 钩子（#136）：拦截在 main/master 上 git commit，以及 git push --force/-f/--force-with-lease。
# 输入是 Claude Code 传来的 JSON（tool_input.command、cwd）；退出码 2 表示拒绝，stderr 转给 Claude。
set -euo pipefail

input=$(cat)
field() {
  printf '%s' "$input" | python3 -c 'import json, sys; d = json.load(sys.stdin); print(d.get("tool_input", {}).get("command", "") if sys.argv[1] == "command" else d.get("cwd", "."))' "$1"
}
command=$(field command)
cwd=$(field cwd)

git_sub='(^|[;&|[:space:]])git[[:space:]]([^;&|]*[[:space:]])?'

if printf '%s' "$command" | grep -Eq "${git_sub}push[[:space:]]([^;&|]*[[:space:]])?(--force|--force-with-lease|-f)([[:space:]=]|$)"; then
  echo "不允许强推（git push --force/-f）：冲突在本地解决后普通推送。" >&2
  exit 2
fi

if printf '%s' "$command" | grep -Eq "${git_sub}commit([[:space:]]|$)"; then
  branch=$(git -C "$cwd" branch --show-current 2>/dev/null || true)
  if [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
    echo "不允许在 $branch 上提交：先从最新 main 新建功能分支（见 AGENTS.md“开工前”）。" >&2
    exit 2
  fi
fi

exit 0
