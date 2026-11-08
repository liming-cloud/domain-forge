#!/usr/bin/env bash
# MVP 闭环验证：在仓库的干净副本中用模板生成带示例的业务服务，与真实 IAM 一起按部署参考启动整套服务，
# 经 Nginx 与网关走完 IAM 授权的完整链路：
#   注册账号 → 未授权被拒 → 安装权限目录、建角色授予、分配角色 → 业务命令与范围查询成功 →
#   跨服务调用（board-service 以同一用户身份读取 order-service 的订单：权限与范围由被调方判定，被调方停机时 503）→
#   集成事件（order-service 提交订单同事务写 Outbox，board-service 消费 OrderSubmitted 维护订单统计；RocketMQ 停机期间
#   提交的事件在恢复后继续发送）→ 链路追踪（临时 Jaeger 中查到一条贯穿网关、业务服务、IAM 与消息消费方的链路）→
#   指标（临时 Prometheus 在内部网络抓取各服务的 /actuator/prometheus）→
#   日志（各服务逐行输出 ECS JSON，同一请求的 traceId 在网关、业务服务与 IAM 的日志中都能找到）→
#   幂等提交（同一 Idempotency-Key 重复提交订单只执行一次）→ 文件存储（凭证经 Nginx 与网关上传、下载，按订单范围授权）→ 网关按账号限流凭证上传 →
#   备份 → 备份后继续写入 → 恢复（含业务服务 schema 与属主、文件卷）→ 撤销角色后立即被拒。
# 同时核对网关按生成的路由转发、绕过网关直连业务服务被拒、业务服务经 Nacos 发现 IAM、从 Nacos 读取自身配置、
# 数据库账号读不到 IAM 的表、/internal/ 不对外。不修改当前工作区。
#
# 用法：scripts/verify_closure.sh
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
copy="$work/repo"
export COMPOSE_PROJECT_NAME="dfclosure$$"
export COMPOSE_FILE="$copy/deploy/compose.yml:$copy/deploy/services/order-service.compose.yml:$copy/deploy/services/board-service.compose.yml:$copy/scripts/compose.tracing-verify.yml:$copy/scripts/compose.metrics-verify.yml"
# shellcheck source=scripts/deploy_lib.sh
. "$repo/scripts/deploy_lib.sh"
port=$(free_port)
base="http://127.0.0.1:$port"
env_file="$work/df.env"
tag="closure$$"
compose() { docker compose --env-file "$env_file" "$@"; }
cleanup() {
    [ $? -eq 0 ] || dump_diagnostics
    compose down -v --remove-orphans > /dev/null 2>&1 || true
    docker image rm -f "domain-forge/iam:$tag" "domain-forge/order-service:$tag" "domain-forge/gateway:$tag" \
        "domain-forge/board-service:$tag" > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

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

step "启动整套服务"
service_db_pw=$(secret)
write_env "$port" "IAM_IMAGE_TAG=$tag" "GATEWAY_IMAGE_TAG=$tag" "ORDER_SERVICE_IMAGE_TAG=$tag" \
    "ORDER_SERVICE_DB_PASSWORD=$service_db_pw" "BOARD_SERVICE_IMAGE_TAG=$tag" "BOARD_SERVICE_DB_PASSWORD=$(secret)"
up_wait --build --quiet-pull > /dev/null 2>&1 || { compose ps -a; compose logs --tail 80; fail "整套服务未全部健康"; }
published=$(compose ps --format '{{.Ports}}' | grep -c -- '->' || true)
[ "$published" = "1" ] || fail "应只有 nginx 发布主机端口"
# 服务以 config-center 启动：能通过健康检查即说明已从 Nacos 读到带来源标记的 order-service.yaml。
grep -q 'nacos config seeded: order-service.yaml' <<< "$(compose logs nacos-init)" || fail "生成服务的配置种子未发布"
grep -q -- '- id: order-service' "$copy/deploy/nacos/config/gateway.yaml" || fail "生成服务的网关路由未写入种子"
grep -q -- '- id: board-service-calls' "$copy/deploy/nacos/config/gateway.yaml" || fail "调用方示例的网关路由未写入种子"
grep -q 'rocketmq group ready: board-service-order-tally' <<< "$(compose logs rocketmq-init)" || fail "消费组未创建"

step "初始化管理员并注册账号"
admin_pw="Admin-$(secret)"
alice_pw="Alice-$(secret)"
bob_pw="Bob-$(secret)"
initialize_admin "$admin_pw"
admin_token=$(token admin "$admin_pw")
alice=$(register alice "$alice_pw" "$admin_token")
alice_id=$(field id <<< "$alice")
alice_version=$(field version <<< "$alice")
register bob "$bob_pw" "$admin_token" > /dev/null
alice_token=$(token alice "$alice_pw")
bob_token=$(token bob "$bob_pw")

step "未授权时的业务服务行为"
get /api/me/orders > /dev/null
expect 401 "无令牌访问业务服务"
get /api/me/orders "$alice_token" > /dev/null
expect 200 "仅需登录的纯查询（共用会话 Redis）"
post /api/orders '{}' "$alice_token" > /dev/null
expect 403 "无权限的业务命令（经 Nacos 发现的 IAM 判定）"
[ "$(get_status /internal/access/current "$alice_token")" = "404" ] || fail "/internal/ 不应对外"
direct=$(compose exec -T gateway curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $alice_token" \
    http://order-service:8090/api/me/orders)
[ "$direct" = "401" ] || fail "绕过网关直连业务服务应被拒：$direct"

step "安装权限目录、建角色授予并分配"
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
assigned=$(put "/api/accounts/$alice_id/roles" "{\"expectedVersion\":$alice_version,\"roleIds\":[$role_id]}" "$admin_token")
expect 200 "分配角色"

step "授权后的命令与范围查询"
# 订单行 JSON：一行 2 件、单价 15 元，小计 3000 分。
order_line='{"sku":"A-1","quantity":2,"unitPriceCents":1500}'
submit_order() { post "/api/orders/$1/submit" '{"expectedVersion":1}' "$alice_token" > /dev/null; }
new_order() { post /api/orders "{\"lines\":[$order_line]}" "${1:-$alice_token}" | field id; }
# 幂等提交：同一 Idempotency-Key 经 Nginx 与网关重复提交，只执行一次并返回首次结果（后面的统计同时证明）。
keyed_submit() {
    curl -sS -o "$work/body" -D "$work/headers" -w '%{http_code}' -X POST "$base/api/orders/$order_id/submit" \
        -H 'Content-Type: application/json' -H "Authorization: Bearer $alice_token" \
        -H "Idempotency-Key: $1" -d '{"expectedVersion":1}' > "$work/status"
    cat "$work/body"
}
order_id=$(new_order)
expect 200 "已授权的业务命令：新建草稿订单"
[ "$(get /api/orders "$alice_token" | size)" = "1" ] || fail "alice 按本人范围应看到 1 张订单"
[ "$(get /api/orders "$alice_token" | python3 -I -c 'import json, sys; print(json.load(sys.stdin)[0]["totalCents"])')" = "3000" ] \
    || fail "订单总额应为订单行小计之和"
post "/api/orders/$order_id/pay" '{"expectedVersion":1}' "$alice_token" > /dev/null
expect 409 "草稿订单不能直接支付（状态机拒绝）"
idempotency_key=$(python3 -I -c 'import uuid; print(uuid.uuid4())')
[ "$(keyed_submit "$idempotency_key" | field status)" = "SUBMITTED" ] || fail "提交后订单应为已提交"
expect 200 "提交订单"
[ "$(keyed_submit "$idempotency_key" | field id)" = "$order_id" ] || fail "同一幂等键的重复提交应返回首次结果"
grep -qi '^idempotent-replayed: true' "$work/headers" || fail "重复提交的响应应标记 Idempotent-Replayed"
[ "$(get /api/me/orders "$alice_token" | size)" = "1" ] || fail "alice 本人订单应为 1 张"
get /api/orders "$bob_token" > /dev/null
expect 403 "未分配角色的账号读取订单"

# tally_is <期望值> [stable]：等 board-service 的订单统计（事件累加，最终一致）达到期望值；
# 带 stable 时再观察 5 秒，期间不得变化（迟到的重复投递会让统计超过期望值）
tally_is() {
    local attempt value
    for attempt in $(seq 1 60); do
        value=$(get /api/board/order-tally "$alice_token" | python3 -I -c 'import json, sys; print(json.load(sys.stdin))')
        [ "$value" = "$1" ] && break
        sleep 1
    done
    [ "$value" = "$1" ] || fail "订单统计应为 $1，实际 $value"
    [ "${2:-}" = "stable" ] || return 0
    sleep 5
    value=$(get /api/board/order-tally "$alice_token" | python3 -I -c 'import json, sys; print(json.load(sys.stdin))')
    [ "$value" = "$1" ] || fail "订单统计应稳定在 $1（疑有重复投递），5 秒后为 $value"
}

step "集成事件：order-service 发布 OrderSubmitted，board-service 累加统计"
tally_is 1

step "链路追踪：一条链路贯穿网关、order-service、IAM 与 board-service"
curl_image=$(sed -n 's/^    image: \(curlimages\/curl:.*\)$/\1/p' "$copy/deploy/compose.yml" | head -1)
# Jaeger 2 只提供 v3 查询接口（OTLP JSON）：按 traceId 汇总各 span 所属的服务。
trace_services='import json, sys
traces = {}
for rs in json.load(sys.stdin).get("result", {}).get("resourceSpans", []):
    attrs = {a["key"]: a["value"].get("stringValue") for a in rs.get("resource", {}).get("attributes", [])}
    for scope in rs.get("scopeSpans", []):
        for span in scope.get("spans", []):
            traces.setdefault(span["traceId"], set()).add(attrs.get("service.name"))
for services in traces.values():
    print(",".join(sorted(str(s) for s in services)))'
window=$(python3 -I -c 'import datetime as d; n = d.datetime.now(d.timezone.utc); f = "%Y-%m-%dT%H:%M:%SZ"
print("query.start_time_min=" + (n - d.timedelta(hours=1)).strftime(f) + "&query.start_time_max=" + (n + d.timedelta(hours=1)).strftime(f))')
found=""
for attempt in $(seq 1 30); do
    found=$(docker run --rm --network "${COMPOSE_PROJECT_NAME}_backend" "$curl_image" -s \
        "http://jaeger:16686/api/v3/traces?query.service_name=board-service&query.search_depth=50&$window" \
        | python3 -I -c "$trace_services" 2>/dev/null || true)
    echo "$found" | grep -qx "board-service,gateway,iam,order-service" && break
    sleep 2
done
echo "$found" | grep -qx "board-service,gateway,iam,order-service" \
    || fail "Jaeger 中应有一条贯穿 gateway、order-service、iam、board-service 的链路；查到的链路：${found:-无}"

step "指标：临时 Prometheus 在内部网络抓取各服务的 /actuator/prometheus"
# promql <查询>：经内部网络向临时 Prometheus 查询，输出每条结果的 instance=值
promql() {
    docker run --rm --network "${COMPOSE_PROJECT_NAME}_backend" "$curl_image" -s -G \
        --data-urlencode "query=$1" http://prometheus:9090/api/v1/query \
        | python3 -I -c 'import json, sys
for r in json.load(sys.stdin).get("data", {}).get("result", []):
    print(r["metric"].get("instance", "-") + "=" + r["value"][1])' 2>/dev/null || true
}
for attempt in $(seq 1 30); do
    up=$(promql 'up' | grep -c '=1$' || true)
    [ "$up" = "4" ] && break
    sleep 2
done
[ "$up" = "4" ] || fail "Prometheus 应能抓取 gateway、iam、order-service、board-service 四个目标：$(promql 'up' | tr '\n' ' ')"
# metric_present <查询> <instance>：抓取周期为秒级，轮询到该实例的结果出现
metric_present() {
    local attempt
    for attempt in $(seq 1 30); do
        promql "$1" | grep -q "^$2=" && return 0
        sleep 2
    done
    return 1
}
for target in gateway:9093 iam:9091 order-service:9092; do
    metric_present 'sum by (instance) (http_server_requests_seconds_count)' "$target" \
        || fail "$target 的 HTTP 请求指标应可查询"
done
metric_present 'domainforge_messaging_outbox_pending' order-service:9092 \
    || fail "order-service 的 Outbox 积压指标应可查询"

step "日志：各服务逐行输出 ECS JSON，同一请求的 traceId 在 gateway、order-service、IAM 的日志中都能找到"
log_trace=$(curl -sS -o /dev/null -D - -H "Authorization: Bearer $alice_token" "$base/api/orders" \
    | tr -d '\r' | sed -n 's/^[Xx]-[Tt]race-[Ii]d: //p')
[[ "$log_trace" =~ ^[0-9a-f]{32}$ ]] || fail "响应应带 traceId：$log_trace"
for service in gateway order-service iam; do
    for attempt in $(seq 1 10); do
        result=$(check_json_logs "$service" "$log_trace")
        [ "$result" = "ok" ] && break
        sleep 1
    done
    [ "$result" = "ok" ] || fail "$service 的日志：$result"
done

step "跨服务调用：board-service 以同一用户身份读取 order-service"
digest=$(get /api/board/order-digest "$alice_token")
expect 200 "调用方读取被调方订单"
[ "$(python3 -I -c 'import json, sys; print(json.load(sys.stdin)["count"])' <<< "$digest")" = "1" ] \
    || fail "调用方应按 alice 的本人范围得到 1 张订单：$digest"
[ "$(python3 -I -c 'import json, sys; print(json.load(sys.stdin)["totalCents"])' <<< "$digest")" = "3000" ] \
    || fail "调用方应读到被调方订单的总额：$digest"
get /api/board/order-digest "$bob_token" > /dev/null
expect 403 "被调方的无权限判定应原样到达调用方"
get /api/board/order-digest > /dev/null
expect 401 "无令牌访问调用方"
compose stop order-service > /dev/null 2>&1
get /api/board/order-digest "$alice_token" > /dev/null
expect 503 "被调方停机时调用方返回不可用"
up_wait order-service > /dev/null 2>&1 || fail "order-service 重新启动未健康"
for attempt in $(seq 1 30); do
    [ "$(get_status /api/board/order-digest "$alice_token")" = "200" ] && break
    [ "$attempt" = "30" ] && fail "被调方恢复后调用方应恢复正常"
    sleep 1
done

# upload <订单 ID> <文件> <令牌>：以 multipart 上传凭证，输出响应体，HTTP 状态写入 $work/status
upload() {
    curl -sS -o "$work/body" -w '%{http_code}' -H "Authorization: Bearer $3" \
        -F "file=@$2;type=application/pdf;filename=季度报告.pdf" "$base/api/orders/$1/files" > "$work/status"
    cat "$work/body"
}
# download <订单 ID> <文件 ID> <令牌>：下载到 $work/download，输出 HTTP 状态
download() {
    curl -sS -o "$work/download" -w '%{http_code}' -H "Authorization: Bearer $3" "$base/api/orders/$1/files/$2"
}
sha256() { python3 -I -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"; }

step "文件存储：凭证经 Nginx 与网关上传、下载，按订单范围授权"
# 2 MB 超过普通接口 64k 的请求体上限，验证 Nginx 只对 /files 路径放宽。
head -c 2097152 /dev/urandom > "$work/before.pdf"
file_id=$(upload "$order_id" "$work/before.pdf" "$alice_token" | field id)
expect 200 "上传凭证"
[ "$(download "$order_id" "$file_id" "$alice_token")" = "200" ] || fail "下载凭证"
[ "$(sha256 "$work/download")" = "$(sha256 "$work/before.pdf")" ] || fail "下载内容与上传不一致"
[ "$(download "$order_id" "$file_id" "$bob_token")" = "403" ] || fail "无权限账号下载凭证应 403"
# 文件存储计数器在首次写入时登记，等下一轮抓取后可在 Prometheus 中查询。
for attempt in $(seq 1 15); do
    promql 'domainforge_files_stored_total' | grep -q '^order-service:9092=' && break
    [ "$attempt" = "15" ] && fail "order-service 的文件存储指标应可查询"
    sleep 2
done
post /api/orders '{"lines":[{"sku":"'"$(head -c 70000 /dev/zero | tr '\0' x)"'","quantity":1,"unitPriceCents":1}]}' "$alice_token" > /dev/null
expect 413 "普通接口仍限制请求体为 64k"

step "网关按路由与账号限流：凭证上传"
# 种子中订单凭证上传按账号突发 10 次。bob 没有权限，后端返回 403，但网关按账号计数在先：突发用尽后 429。
head -c 1024 /dev/urandom > "$work/small.pdf"
denied=0
limited=""
for attempt in $(seq 1 15); do
    upload "$order_id" "$work/small.pdf" "$bob_token" > /dev/null
    case "$(cat "$work/status")" in
        403) denied=$((denied + 1)) ;;
        429) limited=yes; break ;;
        *) fail "凭证上传限流：意外状态 $(cat "$work/status") $(cat "$work/body")" ;;
    esac
done
[ -n "$limited" ] && [ "$denied" -ge 10 ] || fail "按账号突发 10 次后应 429：403 次数 $denied，是否 429：${limited:-否}"
grep -q '"code":"RATE_LIMITED"' "$work/body" || fail "限流响应应为 RATE_LIMITED"
small_id=$(upload "$order_id" "$work/small.pdf" "$alice_token" | field id)
expect 200 "其他账号的凭证上传不受 bob 的限流影响"
call DELETE "/api/orders/$order_id/files/$small_id" "" "$alice_token" > /dev/null
expect 204 "删除用于验证的凭证"

step "数据库账号隔离"
isolation=$(compose exec -T -e PGPASSWORD="$service_db_pw" postgres \
    psql -h 127.0.0.1 -U order_service -d domainforge -tAc 'select count(*) from public.iam_account' 2>&1 || true)
grep -q 'permission denied' <<< "$isolation" || fail "业务服务账号不应读到 IAM 的表：$isolation"
[ "$(sql "select tableowner from pg_tables where schemaname = 'order_service' and tablename = 'orders'")" = "order_service" ] \
    || fail "业务表应属于服务自己的账号"

step "备份、继续写入、恢复"
dump=$("$repo/deploy/backup.sh" "$env_file" "$work/backups" 2> "$work/backup.log" | sed -n 's/^backup=\([^ ]*\) .*/\1/p') \
    || { cat "$work/backup.log"; fail "备份脚本"; }
ls "${dump%.dump}.files.order-service.tar" > /dev/null || fail "备份应包含 order-service 的文件卷"
post "/api/orders/$order_id/cancel" '{"expectedVersion":2}' "$alice_token" > /dev/null
expect 200 "备份后取消订单"
second_id=$(new_order)
expect 200 "备份后写入"
submit_order "$second_id"
expect 200 "备份后提交订单"
head -c 1024 /dev/urandom > "$work/after.pdf"
upload "$order_id" "$work/after.pdf" "$alice_token" > /dev/null
expect 200 "备份后上传凭证"
# 按恢复前提：停止写入后等备份之后的事件消费完毕，再恢复（否则恢复后消费方会处理发布方已回滚的事件，见 #61）。
tally_is 2
"$repo/deploy/restore.sh" "$env_file" "$dump" --yes > /dev/null 2> "$work/restore.log" \
    || { cat "$work/restore.log"; fail "恢复脚本"; }
get /api/me/orders "$alice_token" > /dev/null
expect 401 "恢复前的会话应失效"
alice_token=$(token alice "$alice_pw")
[ "$(get /api/orders "$alice_token" | size)" = "1" ] || fail "恢复后应只有备份前的 1 张订单"
[ "$(get /api/orders "$alice_token" | python3 -I -c 'import json, sys; print(json.load(sys.stdin)[0]["status"])')" = "SUBMITTED" ] \
    || fail "恢复后订单应回到备份时刻的已提交状态"
[ "$(get "/api/orders/$order_id/files" "$alice_token" | size)" = "1" ] || fail "恢复后应只有备份前的 1 个凭证"
[ "$(download "$order_id" "$file_id" "$alice_token")" = "200" ] || fail "恢复后下载备份前的凭证"
[ "$(sha256 "$work/download")" = "$(sha256 "$work/before.pdf")" ] || fail "恢复后凭证内容与上传不一致"
third_id=$(new_order)
expect 200 "恢复后业务服务仍可写入自己的 schema"
submit_order "$third_id"
expect 200 "恢复后提交订单"
tally_is 2 stable

step "集成事件：RocketMQ 停机期间提交的事件在恢复后继续发送"
compose stop rocketmq-broker > /dev/null 2>&1
outage_id=$(new_order)
submit_order "$outage_id"
expect 200 "RocketMQ 停机时业务提交不受影响"
[ "$(sql "select count(*) from order_service.domainforge_outbox where sent_at is null")" -ge 1 ] \
    || fail "停机期间的事件应留在 Outbox 等待发送"
up_wait rocketmq-broker > /dev/null 2>&1 || fail "RocketMQ 重新启动未健康"
tally_is 3

step "撤销角色后立即生效"
admin_token=$(token admin "$admin_pw")
put "/api/accounts/$alice_id/roles" "{\"expectedVersion\":$(field version <<< "$assigned"),\"roleIds\":[]}" "$admin_token" > /dev/null
expect 200 "撤销角色"
post /api/orders '{}' "$alice_token" > /dev/null
expect 403 "撤权后同一会话的业务命令"

echo "closure_check=passed"
