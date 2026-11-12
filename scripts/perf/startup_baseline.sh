#!/usr/bin/env bash
# 启动耗时基线（手动执行，不进 CI；口径与复现说明见 docs/platform/性能基线.md 的“启动”一节）：
# 在仓库的干净副本中生成 order-service，按部署参考启动整套服务并初始化管理员；依赖保持运行，逐个重启被测服务计时：
# 停止容器、用 docker update 设 CPU 配额、启动，记容器启动到就绪、日志中的 Started 秒数、经 Nginx 与网关的首个请求耗时。
# 计时之后每个服务在 1 核下带 JFR 再启动一次，汇总 main 线程的耗时分布。不修改当前工作区。
#
# 用法：scripts/perf/startup_baseline.sh <结果目录>
#   RUNS（每组次数，默认 5）、CPUS（默认 "1 2"）、SERVICES（默认 "iam order-service"）经环境变量调整。
#   KEEP=1 时结束后保留整套服务供排查，并打印清理命令。
set -euo pipefail

[ $# -eq 1 ] || { echo "用法：$0 <结果目录>" >&2; exit 2; }
repo="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$1"
results="$(cd "$1" && pwd)"
runs=${RUNS:-5}
read -r -a cpus <<< "${CPUS:-1 2}"
read -r -a services <<< "${SERVICES:-iam order-service}"
work="$(mktemp -d)"
copy="$work/repo"
export COMPOSE_PROJECT_NAME="dfstart$$"
export COMPOSE_FILE="$copy/deploy/compose.yml:$copy/deploy/services/order-service.compose.yml"
# shellcheck source=scripts/deploy_lib.sh
. "$repo/scripts/deploy_lib.sh"
port=$(free_port)
base="http://127.0.0.1:$port"
env_file="$work/df.env"
tag="start$$"
compose() { docker compose --env-file "$env_file" "$@"; }
cleanup() {
    [ $? -eq 0 ] || dump_diagnostics
    if [ "${KEEP:-}" = 1 ]; then
        echo "已保留：入口 ${base}，环境文件 ${env_file}；清理：COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME COMPOSE_FILE=$COMPOSE_FILE docker compose --env-file $env_file down -v && rm -rf $work"
        return
    fi
    compose down -v --remove-orphans > /dev/null 2>&1 || true
    docker image rm -f "domain-forge/iam:$tag" "domain-forge/order-service:$tag" "domain-forge/gateway:$tag" > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

# 被测服务的管理端口（就绪检查）与首个请求：IAM 查角色列表（权限判定与数据库），order-service 查本人订单
# （共用会话 Redis 与本服务数据库，不调 IAM）。
management_port() { case $1 in iam) echo 9091 ;; *) echo 9092 ;; esac; }
first_path() { case $1 in iam) echo /api/roles ;; *) echo /api/me/orders ;; esac; }
measure() { python3 -I "$copy/scripts/perf/startup.py" measure "$(compose ps -aq "$1")" "$(management_port "$1")" \
    "$base" "$(first_path "$1")" "$work/token" "${2:-}"; }

step "在干净副本中生成 order-service"
mkdir -p "$copy"
# 只复制受版本管理的文件（含未提交修改），避免带入本地构建产物。
(cd "$repo" && git ls-files -z --cached --others --exclude-standard \
    | while IFS= read -r -d '' file; do [ -e "$file" ] && printf '%s\0' "$file"; done \
    | xargs -0 tar -cf -) | (cd "$copy" && tar -xf -)
python3 -I "$copy/platform/templates/ddd-service/generate.py" --root "$copy" \
    --name order-service --package org.example.order --with-example > /dev/null

step "构建 IAM、网关与 order-service"
"$copy/platform/mvnw" -f "$copy/platform/pom.xml" -B -ntp -q clean package \
    -DskipTests -Ddocker.skip=true -pl services/iam,services/gateway,services/order-service -am

step "启动整套服务，初始化管理员"
write_env "$port" "IAM_IMAGE_TAG=$tag" "GATEWAY_IMAGE_TAG=$tag" "ORDER_SERVICE_IMAGE_TAG=$tag" \
    "ORDER_SERVICE_DB_PASSWORD=$(secret)"
up_wait --build --quiet-pull > "$results/compose-up.log" 2>&1 || { compose ps -a; compose logs --tail 80; fail "整套服务未全部健康"; }
admin_pw="Admin-$(secret)"
initialize_admin "$admin_pw"
(umask 077 && token admin "$admin_pw" > "$work/token")
{
    echo "commit $(git -C "$repo" rev-parse HEAD)$(git -C "$repo" diff --quiet HEAD || echo '（含未提交修改）')"
    echo "date $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "host $(uname -sm) $(sysctl -n machdep.cpu.brand_string 2>/dev/null || true)"
    echo "docker $(docker info --format '{{.ServerVersion}} {{.NCPU}} CPU {{.MemTotal}} B')"
    echo "other_containers $(docker ps --format '{{.Names}}' | grep -vc "^$COMPOSE_PROJECT_NAME-" || true)"
} > "$results/environment.txt"

: > "$results/runs.jsonl"
for service in "${services[@]}"; do
    for cpu in "${cpus[@]}"; do
        for run in $(seq 1 "$runs"); do
            step "计时：${service}，${cpu} 核，第 $run 次"
            compose stop "$service" > /dev/null 2>&1
            docker update --cpus "$cpu" "$(compose ps -aq "$service")" > /dev/null
            line=$(measure "$service")
            echo "$line"
            python3 -I -c 'import json, sys; print(json.dumps({"service": sys.argv[1], "cpus": float(sys.argv[2]), "run": int(sys.argv[3])} | json.loads(sys.argv[4])))' \
                "$service" "$cpu" "$run" "$line" >> "$results/runs.jsonl"
        done
    done
done

# JFR：profile 设置，CPU 采样周期 1ms（默认 10ms 时一次启动只有约百个样本）；调用栈加深到 512 帧，避免 Spring 的深栈被截断。
overlay="$work/jfr.compose.yml"
{
    echo "services:"
    for service in "${services[@]}"; do
        cat <<EOF
  $service:
    cpus: 1
    environment:
      JAVA_TOOL_OPTIONS: >-
        -XX:FlightRecorderOptions:stackdepth=512
        -XX:StartFlightRecording=filename=/tmp/startup.jfr,dumponexit=true,settings=profile,jdk.ExecutionSample#period=1ms
EOF
    done
} > "$overlay"
for service in "${services[@]}"; do
    step "剖析：${service}，1 核，JFR"
    COMPOSE_FILE="$COMPOSE_FILE:$overlay" compose up --no-start --no-deps --force-recreate "$service" > /dev/null 2>&1
    line=$(measure "$service" "$results/profile-$service.log")
    compose stop "$service" > /dev/null 2>&1
    docker cp "$(compose ps -aq "$service")":/tmp/startup.jfr "$results/profile-$service.jfr" > /dev/null
    jfr print --json --stack-depth 512 --events jdk.ExecutionSample,jdk.GarbageCollection,jdk.ClassLoadingStatistics \
        "$results/profile-$service.jfr" > "$work/profile.json"
    python3 -I "$copy/scripts/perf/startup.py" profile "$work/profile.json" "$results/profile-$service.log" "$line" \
        > "$results/profile-$service.md"
    # 按原配置重建，恢复为不带 JFR、不限 CPU。
    compose up -d --wait --wait-timeout 300 --no-deps "$service" > /dev/null 2>&1
done

step "汇总"
python3 -I "$copy/scripts/perf/startup.py" report "$results" | tee "$results/report.md"
