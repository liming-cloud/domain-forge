#!/usr/bin/env bash
# MVP 闭环验证：在仓库的干净副本中用模板生成带示例的业务服务，与真实 IAM 一起按部署参考启动整套服务，
# 经 Nginx 与网关走完 IAM 授权的完整链路：
#   注册账号 → 未授权被拒 → 安装权限目录、建角色授予、分配角色 → 业务命令与范围查询成功 →
#   备份 → 备份后继续写入 → 恢复（含业务服务 schema 与属主）→ 撤销角色后立即被拒。
# 同时核对网关按生成的路由转发、绕过网关直连业务服务被拒、业务服务经 Nacos 发现 IAM、从 Nacos 读取自身配置、
# 数据库账号读不到 IAM 的表、/internal/ 不对外。不修改当前工作区。
#
# 用法：scripts/verify_closure.sh
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
copy="$work/repo"
export COMPOSE_PROJECT_NAME="dfclosure$$"
export COMPOSE_FILE="$copy/deploy/compose.yml:$copy/deploy/services/note-service.compose.yml"
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
        > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

step "在干净副本中生成 note-service"
mkdir -p "$copy"
# 只复制受版本管理的文件（含未提交修改），避免带入本地构建产物。
(cd "$repo" && git ls-files -z --cached --others --exclude-standard \
    | while IFS= read -r -d '' file; do [ -e "$file" ] && printf '%s\0' "$file"; done \
    | xargs -0 tar -cf -) | (cd "$copy" && tar -xf -)
python3 -I "$copy/platform/templates/ddd-service/generate.py" --root "$copy" \
    --name note-service --package org.example.note --with-example > /dev/null

step "构建 IAM、网关与 note-service"
"$copy/platform/mvnw" -f "$copy/platform/pom.xml" -B -ntp -q clean package \
    -DskipTests -Ddocker.skip=true -pl services/iam,services/gateway,services/note-service -am

step "启动整套服务"
service_db_pw=$(secret)
write_env "$port" "IAM_IMAGE_TAG=$tag" "GATEWAY_IMAGE_TAG=$tag" "NOTE_SERVICE_IMAGE_TAG=$tag" \
    "NOTE_SERVICE_DB_PASSWORD=$service_db_pw"
compose up -d --build --wait --quiet-pull > /dev/null 2>&1 || { compose ps -a; compose logs --tail 80; fail "整套服务未全部健康"; }
published=$(compose ps --format '{{.Ports}}' | grep -c -- '->' || true)
[ "$published" = "1" ] || fail "应只有 nginx 发布主机端口"
# 服务以 config-center 启动：能通过健康检查即说明已从 Nacos 读到带来源标记的 note-service.yaml。
grep -q 'nacos config seeded: note-service.yaml' <<< "$(compose logs nacos-init)" || fail "生成服务的配置种子未发布"
grep -q -- '- id: note-service' "$copy/deploy/nacos/config/gateway.yaml" || fail "生成服务的网关路由未写入种子"

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
post /api/notes '{"title":"备份前","content":""}' "$alice_token" > /dev/null
expect 200 "已授权的业务命令"
[ "$(get /api/notes "$alice_token" | size)" = "1" ] || fail "alice 按本人范围应看到 1 条"
[ "$(get /api/me/notes "$alice_token" | size)" = "1" ] || fail "alice 本人便笺应为 1 条"
get /api/notes "$bob_token" > /dev/null
expect 403 "未分配角色的账号读取便笺"

step "数据库账号隔离"
isolation=$(compose exec -T -e PGPASSWORD="$service_db_pw" postgres \
    psql -h 127.0.0.1 -U note_service -d domainforge -tAc 'select count(*) from public.iam_account' 2>&1 || true)
grep -q 'permission denied' <<< "$isolation" || fail "业务服务账号不应读到 IAM 的表：$isolation"
[ "$(sql "select tableowner from pg_tables where schemaname = 'note_service' and tablename = 'note'")" = "note_service" ] \
    || fail "业务表应属于服务自己的账号"

step "备份、继续写入、恢复"
dump=$("$repo/deploy/backup.sh" "$env_file" "$work/backups" 2> "$work/backup.log" | sed -n 's/^backup=\([^ ]*\) .*/\1/p') \
    || { cat "$work/backup.log"; fail "备份脚本"; }
post /api/notes '{"title":"备份后","content":""}' "$alice_token" > /dev/null
expect 200 "备份后写入"
"$repo/deploy/restore.sh" "$env_file" "$dump" --yes > /dev/null 2> "$work/restore.log" \
    || { cat "$work/restore.log"; fail "恢复脚本"; }
get /api/me/notes "$alice_token" > /dev/null
expect 401 "恢复前的会话应失效"
alice_token=$(token alice "$alice_pw")
[ "$(get /api/notes "$alice_token" | size)" = "1" ] || fail "恢复后应只有备份前的 1 条便笺"
post /api/notes '{"title":"恢复后","content":""}' "$alice_token" > /dev/null
expect 200 "恢复后业务服务仍可写入自己的 schema"

step "撤销角色后立即生效"
admin_token=$(token admin "$admin_pw")
put "/api/accounts/$alice_id/roles" "{\"expectedVersion\":$(field version <<< "$assigned"),\"roleIds\":[]}" "$admin_token" > /dev/null
expect 200 "撤销角色"
post /api/notes '{"title":"撤权后","content":""}' "$alice_token" > /dev/null
expect 403 "撤权后同一会话的业务命令"

echo "closure_check=passed"
