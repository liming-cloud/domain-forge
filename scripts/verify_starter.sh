#!/usr/bin/env bash
# 在仓库的干净副本中演练起步清单（docs/reference/业务仓库起步清单.md）的删除步骤：删除平台专有资产后，
# AGENTS.md 与开发流程不再引用它们，文档链接检查通过，再执行 mvn verify。不修改当前工作区。
#
# 用法：scripts/verify_starter.sh [--docs-only] [额外 Maven 参数]
#   --docs-only  只做引用与文档链接检查，不运行 mvn verify（CI 的文档链接作业使用）
set -euo pipefail

docs_only=false
if [ "${1:-}" = "--docs-only" ]; then
  docs_only=true
  shift
fi

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# 只复制受版本管理的文件（含未提交修改），避免带入本地构建产物。
(cd "$repo" && git ls-files -z --cached --others --exclude-standard \
  | while IFS= read -r -d '' file; do [ -e "$file" ] && printf '%s\0' "$file"; done \
  | xargs -0 tar -cf -) | (cd "$work" && tar -xf -)

# 起步清单步骤 2：删除平台专有资产。
rm -rf "$work/docs/platform"
# 文档检查按 git 跟踪的文件工作，副本需要一个仓库。
(cd "$work" && git init -q && git add -A)

# 业务研发入口不得再引用平台专有资产。
for file in AGENTS.md docs/workflow/01-开发流程.md; do
  if grep -n 'docs/platform\|platform/产品定义\|platform/路线图\|里程碑' "$work/$file"; then
    echo "$file 仍引用平台专有资产" >&2
    exit 1
  fi
done

python3 -I "$work/scripts/check_doc_links.py"

if [ "$docs_only" = false ]; then
  "$work/platform/mvnw" -f "$work/platform/pom.xml" -B -ntp verify "$@"
fi
echo "starter_check=passed"
