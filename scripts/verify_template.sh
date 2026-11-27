#!/usr/bin/env bash
# 在仓库的干净副本中生成两个服务并对它们执行完整门禁（含真实 PostgreSQL／Redis 集成测试）：带订单示例的 template-check，
# 以及以 --call-example 调用它、以 --consume-example 消费其事件的 template-caller。证明模板产出能在本仓库内构建、通过架构规则、接入 IAM 合同，
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
  --name template-caller --package org.example.templatecaller --call-example template-check \
  --consume-example template-check
# 上游组件的测试已由主门禁执行：这里只安装其构建产物，完整门禁只对生成的两个服务执行。
"$work/platform/mvnw" -f "$work/platform/pom.xml" -B -ntp -q install -DskipTests -Ddocker.skip=true \
  -pl services/template-check,services/template-caller -am
# 新生成的服务还没有接口契约快照：以 api.contract.update 模拟本机首次构建写出 openapi.json（持续集成中缺快照会失败），
# 再确认快照包含各自的示例接口。
"$work/platform/mvnw" -f "$work/platform/pom.xml" -B -ntp verify -pl services/template-check,services/template-caller \
  -Dapi.contract.update=true "$@"
grep -q '"/api/orders/{id}/submit"' "$work/platform/services/template-check/openapi.json"
grep -q '/order-digest"' "$work/platform/services/template-caller/openapi.json"
echo "template_check=passed"
