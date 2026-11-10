#!/usr/bin/env bash
# 性能基线（手动执行，不进 CI；口径与复现说明见 docs/platform/性能基线.md）：
# 在仓库的干净副本中生成 order-service 与调用它的 board-service，按部署参考（加 scripts/perf/compose.perf.yml 的压测网络）
# 启动整套服务（内存配额合计 8064m），全部健康后施加 CPU 配额（合计 4 核）；经 Nginx 与网关灌入 1 万个账号、10 万张订单，再用 k6 依次压
# 登录、带范围的订单查询、提交订单、跨服务读取订单摘要，k6 汇总与容器资源采样写入结果目录。不修改当前工作区。
#
# 用法：scripts/perf/run_baseline.sh <结果目录> [场景...]
#   场景默认 login query submit digest；RATE、WARMUP、DURATION、VUS、ACCOUNTS、ORDERS_PER_ACCOUNT 经环境变量传给 k6。
#   KEEP=1 时结束后保留整套服务供排查，并打印清理命令。
set -euo pipefail

[ $# -ge 1 ] || { echo "用法：$0 <结果目录> [场景...]" >&2; exit 2; }
repo="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$1"
results="$(cd "$1" && pwd)"
shift
if [ $# -gt 0 ]; then scenarios=("$@"); else scenarios=(login query submit digest); fi
work="$(mktemp -d)"
copy="$work/repo"
export COMPOSE_PROJECT_NAME="dfperf$$"
export COMPOSE_FILE="$copy/deploy/compose.yml:$copy/deploy/services/order-service.compose.yml:$copy/deploy/services/board-service.compose.yml:$copy/scripts/perf/compose.perf.yml"
# shellcheck source=scripts/deploy_lib.sh
. "$repo/scripts/deploy_lib.sh"
port=$(free_port)
base="http://127.0.0.1:$port"
env_file="$work/df.env"
tag="perf$$"
k6_image=grafana/k6:2.3.0@sha256:3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e
compose() { docker compose --env-file "$env_file" "$@"; }
# 常驻容器的 CPU 配额（服务=核数），合计 4 核；内存配额（合计 8064m）在 compose.perf.yml 中，二者模拟 4 核 8G 的单机。
# CPU 配额在全部健康后施加：逐容器的静态配额下 PowerJob 等 JVM 启动超过健康检查时限，而真实单机启动时各容器共享 CPU。
# 一次性初始化任务在施加配额前已退出。
cpu_quotas=(postgres=0.8 redis=0.2 nacos=0.2 rocketmq-namesrv=0.1 rocketmq-broker=0.2 powerjob-server=0.1
    iam=0.8 gateway=0.6 order-service=0.6 board-service=0.2 nginx=0.2)
cleanup() {
    [ $? -eq 0 ] || dump_diagnostics
    if [ "${KEEP:-}" = 1 ]; then
        echo "已保留：入口 ${base}，环境文件 ${env_file}；清理：COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME COMPOSE_FILE=$COMPOSE_FILE docker compose --env-file $env_file down -v && rm -rf $work"
        return
    fi
    compose down -v --remove-orphans > /dev/null 2>&1 || true
    docker image rm -f "domain-forge/iam:$tag" "domain-forge/order-service:$tag" "domain-forge/gateway:$tag" \
        "domain-forge/board-service:$tag" > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

# k6 <脚本> <汇总文件名> [环境变量...]：在压测网络中运行 k6，汇总写入结果目录
k6() {
    local script=$1 summary=$2 envs=()
    shift 2
    for kv in BASE=http://nginx "PASSWORD=$password" "$@"; do envs+=(-e "$kv"); done
    for kv in ACCOUNTS ORDERS_PER_ACCOUNT RATE WARMUP DURATION VUS; do [ -n "${!kv:-}" ] && envs+=(-e "$kv=${!kv}"); done
    docker run --rm --network "${COMPOSE_PROJECT_NAME}_loadgen" --cap-add NET_ADMIN --user root \
        -v "$copy/scripts/perf:/perf:ro" -v "$results:/results" --entrypoint /perf/k6.sh "${envs[@]}" "$k6_image" \
        --quiet --summary-export "/results/$summary" "/perf/$script"
}

# sample <文件>：每 15 秒记录一次各容器的 CPU 与内存，直到被结束
sample() {
    while true; do
        { date -u +%H:%M:%S; docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' \
            | grep "^$COMPOSE_PROJECT_NAME-" | sed "s/^$COMPOSE_PROJECT_NAME-//"; } >> "$1"
        sleep 15
    done
}

step "在干净副本中生成 order-service 与调用它的 board-service"
mkdir -p "$copy"
# 只复制受版本管理的文件（含未提交修改），避免带入本地构建产物。
(cd "$repo" && git ls-files -z --cached --others --exclude-standard \
    | while IFS= read -r -d '' file; do [ -e "$file" ] && printf '%s\0' "$file"; done \
    | xargs -0 tar -cf -) | (cd "$copy" && tar -xf -)
python3 -I "$copy/platform/templates/ddd-service/generate.py" --root "$copy" \
    --name order-service --package org.example.order --with-example > /dev/null
python3 -I "$copy/platform/templates/ddd-service/generate.py" --root "$copy" \
    --name board-service --package org.example.board --call-example order-service --consume-example order-service \
    > /dev/null

step "构建 IAM、网关、order-service 与 board-service"
"$copy/platform/mvnw" -f "$copy/platform/pom.xml" -B -ntp -q clean package \
    -DskipTests -Ddocker.skip=true -pl services/iam,services/gateway,services/order-service,services/board-service -am

step "启动整套服务并施加资源配额"
write_env "$port" "IAM_IMAGE_TAG=$tag" "GATEWAY_IMAGE_TAG=$tag" "ORDER_SERVICE_IMAGE_TAG=$tag" \
    "ORDER_SERVICE_DB_PASSWORD=$(secret)" "BOARD_SERVICE_IMAGE_TAG=$tag" "BOARD_SERVICE_DB_PASSWORD=$(secret)"
up_wait --build --quiet-pull > "$results/compose-up.log" 2>&1 || { compose ps -a; compose logs --tail 80; fail "整套服务未全部健康"; }
for quota in "${cpu_quotas[@]}"; do
    docker update --cpus "${quota#*=}" "$(compose ps -q "${quota%%=*}")" > /dev/null
done
{
    echo "commit $(git -C "$repo" rev-parse HEAD)$(git -C "$repo" diff --quiet HEAD || echo '（含未提交修改）')"
    echo "date $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "host $(uname -sm) $(sysctl -n machdep.cpu.brand_string 2>/dev/null || true)"
    echo "docker $(docker info --format '{{.ServerVersion}} {{.NCPU}} CPU {{.MemTotal}} B')"
    echo "k6 $k6_image"
    docker inspect --format '{{.Name}} cpus={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}}' \
        $(compose ps -q) | sed "s#^/$COMPOSE_PROJECT_NAME-##"
} > "$results/environment.txt"

step "初始化管理员、安装订单权限目录、建订单角色（本人范围读写）"
admin_pw="Admin-$(secret)"
password="Perf-$(secret)"
initialize_admin "$admin_pw"
admin_token=$(token admin "$admin_pw")
catalog=$(python3 -I -c 'import json, sys; print(json.dumps({"permissions": json.load(open(sys.argv[1]))["permissions"]}))' \
    "$copy/platform/services/order-service/src/main/resources/iam/permissions.json")
put /api/permission-namespaces/order "$catalog" "$admin_token" > /dev/null
expect 200 "安装 order 权限目录"
role=$(post /api/roles '{"code":"order-author","name":"订单作者"}' "$admin_token")
expect 200 "创建角色"
role_id=$(field id <<< "$role")
put "/api/roles/$role_id/grants" "{\"expectedVersion\":$(field version <<< "$role"),\"grants\":[
    {\"permission\":\"order:order:read\",\"scope\":\"SELF\"},
    {\"permission\":\"order:order:write\",\"scope\":\"SELF\"}]}" "$admin_token" > /dev/null
expect 200 "角色授予"

step "灌数：账号与订单"
started=$(date +%s)
k6 seed.js seed.json "ADMIN_TOKEN=$admin_token" "ROLE_ID=$role_id"
{
    echo "seed_seconds $(($(date +%s) - started))"
    echo "accounts $(sql 'select count(*) from public.iam_account')"
    echo "orders $(sql 'select count(*) from order_service.orders')"
} >> "$results/environment.txt"

for scenario in "${scenarios[@]}"; do
    step "压测：$scenario"
    sample "$results/stats-$scenario.txt" &
    sampler=$!
    status=0
    k6 load.js "$scenario.json" "SCENARIO=$scenario" || status=$?
    kill "$sampler"
    wait "$sampler" 2> /dev/null || true
    # 99：门槛未通过，结果照常记录；其他非零为执行失败。
    [ "$status" = 0 ] || [ "$status" = 99 ] || fail "k6 执行失败（${scenario}）：${status}"
done

step "汇总"
python3 -I "$copy/scripts/perf/report.py" "$results" "${scenarios[@]}" | tee "$results/report.md"
