#!/usr/bin/env bash
# PR 过程守卫：标题前缀必须是 feat/bug/build/doc，且 PR 内不含以 wip 开头的提交。
# 用法：check_pr_guard.sh <PR 标题> <base 提交> <head 提交>
set -euo pipefail

title=$1
base=$2
head=$3
status=0

if ! printf '%s' "$title" | grep -Eq '^(feat|bug|build|doc)(\([^)]+\))?: .+'; then
  echo "PR 标题前缀不合规：'$title'。须以 feat:、bug:、build:、doc: 之一开头（见 docs/workflow/01-开发流程.md）。" >&2
  status=1
fi

wip=$(git log --format='%h %s' "$base..$head" | grep -Ei '^[0-9a-f]+ wip' || true)
if [ -n "$wip" ]; then
  echo "PR 含 WIP 提交，请整理后再提交评审：" >&2
  echo "$wip" >&2
  status=1
fi

exit "$status"
