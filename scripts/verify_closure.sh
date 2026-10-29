#!/usr/bin/env bash
# MVP 闭环验证：在仓库的干净副本中用模板生成带示例的业务服务，与真实 IAM 一起按部署参考启动整套服务，
# 经 Nginx 与网关走完 IAM 授权的完整链路：
#   注册账号 → 未授权被拒 → 安装权限目录、建角色授予、分配角色 → 业务命令与范围查询成功 →
#   跨服务调用（board-service 以同一用户身份读取 note-service 的便笺：权限与范围由被调方判定，被调方停机时 503）→
#   集成事件（note-service 创建便笺同事务写 Outbox，board-service 消费 NoteCreated 维护便笺统计；RocketMQ 停机期间
#   提交的事件在恢复后继续发送）→ 链路追踪（临时 Jaeger 中查到一条贯穿网关、业务服务、IAM 与消息消费方的链路）→
#   文件存储（附件经 Nginx 与网关上传、下载，按便笺范围授权）→
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
export COMPOSE_FILE="$copy/deploy/compose.yml:$copy/deploy/services/note-service.compose.yml:$copy/deploy/services/board-service.compose.yml:$copy/scripts/compose.tracing-verify.yml"
# shellcheck source=scripts/deploy_lib.sh
. "$repo/scripts/deploy_lib.sh"
port=$(free_port)
base="http://127.0.0.1:$port"
env_file="$work/df.env"
tag="closure$$"
compose() { docker compose --env-file "$env_file" "$@"; }
cleanup() {
    compose down -v --remove-orphans > /dev/null 2>&1 || true
    docker image rm -f "domain-forge/iam:$tag" "domain-forge/note-service:$tag" "domain-forge/gateway:$tag" \
        "domain-forge/board-service:$tag" > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

step "在干净副本中生成 note-service 与调用它的 board-service"
mkdir -p "$copy"
# 只复制受版本管理的文件（含未提交修改），避免带入本地构建产物。
(cd "$repo" && git ls-files -z --cached --others --exclude-standard \
    | while IFS= read -r -d '' file; do [ -e "$file" ] && printf '%s\0' "$file"; done \
    | xargs -0 tar -cf -) | (cd "$copy" && tar -xf -)
python3 -I "$copy/platform/templates/ddd-service/generate.py" --root "$copy" \
    --name note-service --package org.example.note --with-example > /dev/null
python3 -I "$copy/platform/templates/ddd-service/generate.py" --root "$copy" \
    --name board-service --package org.example.board --call-example note-service --consume-example note-service \
    > /dev/null

step "构建 IAM、网关、note-service 与 board-service"
"$copy/platform/mvnw" -f "$copy/platform/pom.xml" -B -ntp -q clean package \
    -DskipTests -Ddocker.skip=true -pl services/iam,services/gateway,services/note-service,services/board-service -am

step "启动整套服务"
service_db_pw=$(secret)
write_env "$port" "IAM_IMAGE_TAG=$tag" "GATEWAY_IMAGE_TAG=$tag" "NOTE_SERVICE_IMAGE_TAG=$tag" \
    "NOTE_SERVICE_DB_PASSWORD=$service_db_pw" "BOARD_SERVICE_IMAGE_TAG=$tag" "BOARD_SERVICE_DB_PASSWORD=$(secret)"
compose up -d --build --wait --quiet-pull > /dev/null 2>&1 || { compose ps -a; compose logs --tail 80; fail "整套服务未全部健康"; }
published=$(compose ps --format '{{.Ports}}' | grep -c -- '->' || true)
[ "$published" = "1" ] || fail "应只有 nginx 发布主机端口"
# 服务以 config-center 启动：能通过健康检查即说明已从 Nacos 读到带来源标记的 note-service.yaml。
grep -q 'nacos config seeded: note-service.yaml' <<< "$(compose logs nacos-init)" || fail "生成服务的配置种子未发布"
grep -q -- '- id: note-service' "$copy/deploy/nacos/config/gateway.yaml" || fail "生成服务的网关路由未写入种子"
grep -q -- '- id: board-service-calls' "$copy/deploy/nacos/config/gateway.yaml" || fail "调用方示例的网关路由未写入种子"
grep -q 'rocketmq group ready: board-service-note-tally' <<< "$(compose logs rocketmq-init)" || fail "消费组未创建"

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
get /api/me/notes > /dev/null
expect 401 "无令牌访问业务服务"
get /api/me/notes "$alice_token" > /dev/null
expect 200 "仅需登录的纯查询（共用会话 Redis）"
post /api/notes '{"title":"未授权","content":""}' "$alice_token" > /dev/null
expect 403 "无权限的业务命令（经 Nacos 发现的 IAM 判定）"
[ "$(get_status /internal/access/current "$alice_token")" = "404" ] || fail "/internal/ 不应对外"
direct=$(compose exec -T gateway curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $alice_token" \
    http://note-service:8090/api/me/notes)
[ "$direct" = "401" ] || fail "绕过网关直连业务服务应被拒：$direct"

step "安装权限目录、建角色授予并分配"
catalog=$(python3 -I -c 'import json, sys; print(json.dumps({"permissions": json.load(open(sys.argv[1]))["permissions"]}))' \
    "$copy/platform/services/note-service/src/main/resources/iam/permissions.json")
put /api/permission-namespaces/note "$catalog" "$admin_token" > /dev/null
expect 200 "安装 note 权限目录"
role=$(post /api/roles '{"code":"note-author","name":"便笺作者"}' "$admin_token")
expect 200 "创建角色"
role_id=$(field id <<< "$role")
put "/api/roles/$role_id/grants" "{\"expectedVersion\":$(field version <<< "$role"),\"grants\":[
    {\"permission\":\"note:note:read\",\"scope\":\"SELF\"},
    {\"permission\":\"note:note:write\",\"scope\":\"SELF\"}]}" "$admin_token" > /dev/null
expect 200 "角色授予"
assigned=$(put "/api/accounts/$alice_id/roles" "{\"expectedVersion\":$alice_version,\"roleIds\":[$role_id]}" "$admin_token")
expect 200 "分配角色"

step "授权后的命令与范围查询"
note_id=$(post /api/notes '{"title":"备份前","content":""}' "$alice_token" | field id)
expect 200 "已授权的业务命令"
[ "$(get /api/notes "$alice_token" | size)" = "1" ] || fail "alice 按本人范围应看到 1 条"
[ "$(get /api/me/notes "$alice_token" | size)" = "1" ] || fail "alice 本人便笺应为 1 条"
get /api/notes "$bob_token" > /dev/null
expect 403 "未分配角色的账号读取便笺"

# tally_is <期望值>：等 board-service 的便笺统计（事件累加，最终一致）达到期望值
tally_is() {
    local attempt value
    for attempt in $(seq 1 60); do
        value=$(get /api/board/note-tally "$alice_token" | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["data"])')
        [ "$value" = "$1" ] && return
        sleep 1
    done
    fail "便笺统计应为 $1，实际 $value"
}

step "集成事件：note-service 发布 NoteCreated，board-service 累加统计"
tally_is 1

step "链路追踪：一条链路贯穿网关、note-service、IAM 与 board-service"
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
    echo "$found" | grep -qx "board-service,gateway,iam,note-service" && break
    sleep 2
done
echo "$found" | grep -qx "board-service,gateway,iam,note-service" \
    || fail "Jaeger 中应有一条贯穿 gateway、note-service、iam、board-service 的链路；查到的链路：${found:-无}"

step "跨服务调用：board-service 以同一用户身份读取 note-service"
digest=$(get /api/board/note-digest "$alice_token")
expect 200 "调用方读取被调方便笺"
[ "$(python3 -I -c 'import json, sys; print(json.load(sys.stdin)["data"]["count"])' <<< "$digest")" = "1" ] \
    || fail "调用方应按 alice 的本人范围得到 1 条便笺：$digest"
get /api/board/note-digest "$bob_token" > /dev/null
expect 403 "被调方的无权限判定应原样到达调用方"
get /api/board/note-digest > /dev/null
expect 401 "无令牌访问调用方"
compose stop note-service > /dev/null 2>&1
get /api/board/note-digest "$alice_token" > /dev/null
expect 503 "被调方停机时调用方返回不可用"
compose up -d --wait note-service > /dev/null 2>&1 || fail "note-service 重新启动未健康"
for attempt in $(seq 1 30); do
    [ "$(get_status /api/board/note-digest "$alice_token")" = "200" ] && break
    [ "$attempt" = "30" ] && fail "被调方恢复后调用方应恢复正常"
    sleep 1
done

# upload <便笺 ID> <文件> <令牌>：以 multipart 上传附件，输出响应体，HTTP 状态写入 $work/status
upload() {
    curl -sS -o "$work/body" -w '%{http_code}' -H "Authorization: Bearer $3" \
        -F "file=@$2;type=application/pdf;filename=季度报告.pdf" "$base/api/notes/$1/files" > "$work/status"
    cat "$work/body"
}
# download <便笺 ID> <文件 ID> <令牌>：下载到 $work/download，输出 HTTP 状态
download() {
    curl -sS -o "$work/download" -w '%{http_code}' -H "Authorization: Bearer $3" "$base/api/notes/$1/files/$2"
}
sha256() { python3 -I -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"; }

step "文件存储：附件经 Nginx 与网关上传、下载，按便笺范围授权"
# 2 MB 超过普通接口 64k 的请求体上限，验证 Nginx 只对 /files 路径放宽。
head -c 2097152 /dev/urandom > "$work/before.pdf"
file_id=$(upload "$note_id" "$work/before.pdf" "$alice_token" | field id)
expect 200 "上传附件"
[ "$(download "$note_id" "$file_id" "$alice_token")" = "200" ] || fail "下载附件"
[ "$(sha256 "$work/download")" = "$(sha256 "$work/before.pdf")" ] || fail "下载内容与上传不一致"
[ "$(download "$note_id" "$file_id" "$bob_token")" = "403" ] || fail "无权限账号下载附件应 403"
post /api/notes '{"title":"大请求体","content":"'"$(head -c 70000 /dev/zero | tr '\0' x)"'"}' "$alice_token" > /dev/null
expect 413 "普通接口仍限制请求体为 64k"

step "数据库账号隔离"
isolation=$(compose exec -T -e PGPASSWORD="$service_db_pw" postgres \
    psql -h 127.0.0.1 -U note_service -d domainforge -tAc 'select count(*) from public.iam_account' 2>&1 || true)
grep -q 'permission denied' <<< "$isolation" || fail "业务服务账号不应读到 IAM 的表：$isolation"
[ "$(sql "select tableowner from pg_tables where schemaname = 'note_service' and tablename = 'note'")" = "note_service" ] \
    || fail "业务表应属于服务自己的账号"

step "备份、继续写入、恢复"
dump=$("$repo/deploy/backup.sh" "$env_file" "$work/backups" 2> "$work/backup.log" | sed -n 's/^backup=\([^ ]*\) .*/\1/p') \
    || { cat "$work/backup.log"; fail "备份脚本"; }
ls "${dump%.dump}.files.note-service.tar" > /dev/null || fail "备份应包含 note-service 的文件卷"
post /api/notes '{"title":"备份后","content":""}' "$alice_token" > /dev/null
expect 200 "备份后写入"
head -c 1024 /dev/urandom > "$work/after.pdf"
upload "$note_id" "$work/after.pdf" "$alice_token" > /dev/null
expect 200 "备份后上传附件"
"$repo/deploy/restore.sh" "$env_file" "$dump" --yes > /dev/null 2> "$work/restore.log" \
    || { cat "$work/restore.log"; fail "恢复脚本"; }
get /api/me/notes "$alice_token" > /dev/null
expect 401 "恢复前的会话应失效"
alice_token=$(token alice "$alice_pw")
[ "$(get /api/notes "$alice_token" | size)" = "1" ] || fail "恢复后应只有备份前的 1 条便笺"
[ "$(get "/api/notes/$note_id/files" "$alice_token" | size)" = "1" ] || fail "恢复后应只有备份前的 1 个附件"
[ "$(download "$note_id" "$file_id" "$alice_token")" = "200" ] || fail "恢复后下载备份前的附件"
[ "$(sha256 "$work/download")" = "$(sha256 "$work/before.pdf")" ] || fail "恢复后附件内容与上传不一致"
post /api/notes '{"title":"恢复后","content":""}' "$alice_token" > /dev/null
expect 200 "恢复后业务服务仍可写入自己的 schema"
tally_is 2

step "集成事件：RocketMQ 停机期间提交的事件在恢复后继续发送"
compose stop rocketmq-broker > /dev/null 2>&1
post /api/notes '{"title":"消息中断期间","content":""}' "$alice_token" > /dev/null
expect 200 "RocketMQ 停机时业务提交不受影响"
[ "$(sql "select count(*) from note_service.domainforge_outbox where sent_at is null")" -ge 1 ] \
    || fail "停机期间的事件应留在 Outbox 等待发送"
compose up -d --wait rocketmq-broker > /dev/null 2>&1 || fail "RocketMQ 重新启动未健康"
tally_is 3

step "撤销角色后立即生效"
admin_token=$(token admin "$admin_pw")
put "/api/accounts/$alice_id/roles" "{\"expectedVersion\":$(field version <<< "$assigned"),\"roleIds\":[]}" "$admin_token" > /dev/null
expect 200 "撤销角色"
post /api/notes '{"title":"撤权后","content":""}' "$alice_token" > /dev/null
expect 403 "撤权后同一会话的业务命令"

echo "closure_check=passed"
