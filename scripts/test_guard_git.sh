#!/usr/bin/env bash
# .claude/hooks/guard-git.sh 的自测：在临时仓库的 main 与功能分支上，给钩子喂 Claude Code 的 PreToolUse JSON，
# 断言拒绝（退出码 2）或放行（退出码 0）。CI 的文档链接作业执行。
#
# 用法：scripts/test_guard_git.sh
set -euo pipefail

hook="$(cd "$(dirname "$0")/.." && pwd)/.claude/hooks/guard-git.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git init -q -b main "$work/on-main"
git init -q -b main "$work/on-branch"
git -C "$work/on-branch" checkout -q -b feat/1-demo

failures=0
expect() {
  local want="$1" dir="$2" command="$3" got=0
  python3 -c 'import json, sys; print(json.dumps({"cwd": sys.argv[1], "tool_name": "Bash", "tool_input": {"command": sys.argv[2]}}))' \
    "$work/$dir" "$command" | "$hook" 2>/dev/null || got=$?
  if [ "$got" != "$want" ]; then
    echo "失败：$dir 上 \`$command\` 期望退出码 $want，实际 $got" >&2
    failures=$((failures + 1))
  fi
}

expect 2 on-main 'git commit -m x'
expect 2 on-main 'git -C . commit --amend'
expect 2 on-main 'cd foo && git add a && git commit -m y'
expect 0 on-main 'git status'
expect 0 on-main 'git log --oneline -1'
expect 0 on-branch 'git commit -m x'
expect 2 on-branch 'git push --force origin feat/1-demo'
expect 2 on-branch 'git push -f'
expect 2 on-branch 'git push --force-with-lease'
expect 0 on-branch 'git push -u origin feat/1-demo'
expect 0 on-branch 'gh pr create --fill'

if [ "$failures" -gt 0 ]; then
  echo "guard-git.sh 自测失败 $failures 项" >&2
  exit 1
fi
echo "guard-git.sh 自测通过"
