#!/usr/bin/env bash
# 单服务冒烟：只构建一个服务的 jar 与镜像，按部署参考（config-center profile）启动它与 depends_on 带起的依赖，
# 核对容器健康检查通过、标准输出逐行为 ECS JSON。用于确认启动、配置与日志格式这类问题，不必跑完整的
# verify_closure.sh；接口行为、跨服务与恢复场景仍以闭环与部署验证为准。结束时删除本次的容器、卷与镜像。
#
# 用法：scripts/smoke_service.sh <服务>          # iam、gateway，或 platform/services 下已生成的服务
#       SKIP_BUILD=1 scripts/smoke_service.sh <服务>  # 复用已构建的 jar
set -euo pipefail

service="${1:?用法：scripts/smoke_service.sh <服务>}"
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
export COMPOSE_PROJECT_NAME="dfsmoke$$"
# shellcheck source=scripts/deploy_lib.sh
. "$repo/scripts/deploy_lib.sh"
[ -d "$repo/platform/services/$service" ] || fail "没有服务模块 platform/services/$service"
env_file="$work/df.env"
tag="smoke$$"
env_name=$(tr 'a-z-' 'A-Z_' <<< "$service")
# 生成服务的覆盖文件：主文件在前，其余（如归档库）在后。
files=(-f "$repo/deploy/compose.yml")
[ -f "$repo/deploy/services/$service.compose.yml" ] && files+=(-f "$repo/deploy/services/$service.compose.yml")
for overlay in "$repo/deploy/services/$service".*.compose.yml; do
    [ -f "$overlay" ] && files+=(-f "$overlay")
done
compose() { docker compose "${files[@]}" --env-file "$env_file" "$@"; }
cleanup() {
    compose down -v --remove-orphans > /dev/null 2>&1 || true
    docker image rm -f "domain-forge/$service:$tag" > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

write_env "$(free_port)" "${env_name}_IMAGE_TAG=$tag" "${env_name}_DB_PASSWORD=$(secret)"
started=$(date +%s)

if [ "${SKIP_BUILD:-}" != "1" ]; then
    step "构建 $service jar"
    "$repo/platform/mvnw" -f "$repo/platform/pom.xml" -B -ntp -q package -DskipTests -Ddocker.skip=true \
        -pl "services/$service" -am
fi

step "启动 $service 及其依赖（config-center profile）"
compose up -d --build --wait --quiet-pull "$service" > "$work/up.log" 2>&1 \
    || { cat "$work/up.log"; compose ps -a; compose logs --tail 60 "$service"; fail "$service 未通过健康检查"; }

step "标准输出逐行为 ECS JSON"
result=$(check_json_logs "$service")
[ "$result" = "ok" ] || fail "$service 的日志：$result"

echo "smoke_check=passed service=$service seconds=$(($(date +%s) - started))"
