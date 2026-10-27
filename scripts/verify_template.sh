#!/usr/bin/env bash
# 在仓库的干净副本中生成两个服务并执行完整门禁（含真实 PostgreSQL／Redis 集成测试）：带便笺示例的 template-check，
# 以及以 --call-example 调用它的 template-caller。证明模板产出能在本仓库内构建、通过架构规则、接入 IAM 合同，
# 并按跨服务调用组件的合同调用另一服务。不修改当前工作区。
#
# 用法：scripts/verify_template.sh [额外 Maven 参数]，例如本机无 Docker 时：
#   scripts/verify_template.sh -Ddocker.skip=true -Dit.postgres.port=5432 -Dit.redis.port=6379
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# 只复制受版本管理的文件（含未提交修改），避免带入本地构建产物。
(cd "$repo" && git ls-files -z --cached --others --exclude-standard \
  | while IFS= read -r -d '' file; do [ -e "$file" ] && printf '%s\0' "$file"; done \
  | xargs -0 tar -cf -) | (cd "$work" && tar -xf -)

python3 -I "$work/platform/templates/ddd-service/generate.py" --root "$work" \
  --name template-check --package org.example.templatecheck --with-example
python3 -I "$work/platform/templates/ddd-service/generate.py" --root "$work" \
  --name template-caller --package org.example.templatecaller --call-example template-check
"$work/platform/mvnw" -f "$work/platform/pom.xml" -B -ntp verify -pl services/template-check,services/template-caller \
  -am "$@"
echo "template_check=passed"
