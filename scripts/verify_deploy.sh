#!/usr/bin/env bash
# 部署参考的真实场景验证：用独立 Compose 项目和临时密钥启动整套服务，依次验证
#   首次部署与管理员初始化 → 普通重启保留数据 → 备份 → 备份后继续变更 →
#   删除数据库卷模拟数据丢失 → 恢复 → 数据回到备份时刻、旧会话全部失效。
# 结果以接口行为判定，不以容器启动或备份文件存在代替；结束时删除本次创建的容器、卷和临时文件。
#
# 用法：scripts/verify_deploy.sh            # 先 clean package 构建 IAM jar
#       SKIP_BUILD=1 scripts/verify_deploy.sh  # 复用已构建的 jar
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
export COMPOSE_PROJECT_NAME="dfverify$$"
port=$(python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
base="http://127.0.0.1:$port"
env_file="$work/df.env"
compose() { docker compose -f "$repo/deploy/compose.yml" --env-file "$env_file" "$@"; }
cleanup() {
    compose down -v --remove-orphans > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

step() { echo "== $*"; }
fail() { echo "deploy_check=failed: $*" >&2; exit 1; }
secret() { python3 -I -c 'import secrets; print(secrets.token_hex(24))'; }
key() { python3 -I -c 'import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())'; }
field() { python3 -I -c 'import json, sys; print(json.load(sys.stdin)["data"][sys.argv[1]])' "$1"; }

# post <路径> <JSON> [令牌]：输出响应体，HTTP 状态写入 $work/status
post() {
    local auth=()
    [ $# -ge 3 ] && auth=(-H "Authorization: Bearer $3")
    curl -sS -o "$work/body" -w '%{http_code}' -X POST "$base$1" -H 'Content-Type: application/json' \
        ${auth[@]+"${auth[@]}"} -d "$2" > "$work/status"
    cat "$work/body"
}
get_status() { curl -sS -o /dev/null -w '%{http_code}' "$base$1" -H "Authorization: Bearer $2"; }
expect() { [ "$(cat "$work/status")" = "$1" ] || fail "$2：期望 $1，实际 $(cat "$work/status")"; }
login() { post /api/auth/login "{\"loginName\":\"$1\",\"password\":\"$2\"}" > /dev/null; cat "$work/status"; }
token() { post /api/auth/login "{\"loginName\":\"$1\",\"password\":\"$2\"}" | field token; }
register() {
    local invitation
    invitation=$(post /api/invitations "{\"loginName\":\"$1\"}" "$3" | field secret)
    expect 200 "邀请 $1"
    post /api/auth/invitations/accept \
        "{\"invitationSecret\":\"$invitation\",\"loginName\":\"$1\",\"password\":\"$2\"}" > /dev/null
    expect 200 "注册 $1"
}

cat > "$env_file" <<EOF
POSTGRES_PASSWORD=$(secret)
REDIS_PASSWORD=$(secret)
NACOS_AUTH_TOKEN=$(key)
NACOS_AUTH_IDENTITY_KEY=$(secret)
NACOS_AUTH_IDENTITY_VALUE=$(secret)
NACOS_ADMIN_PASSWORD=$(secret)
NACOS_SERVICE_USERNAME=domainforge
NACOS_SERVICE_PASSWORD=$(secret)
IAM_FINGERPRINT_KEY=$(key)
NGINX_HTTP_BIND=127.0.0.1
NGINX_HTTP_PORT=$port
EOF
chmod 600 "$env_file"
admin_pw="Admin-$(secret)"
alice_pw="Alice-$(secret)"
alice_new_pw="Alice-new-$(secret)"
bob_pw="Bob-$(secret)"

if [ "${SKIP_BUILD:-}" != "1" ]; then
    step "构建 IAM jar"
    "$repo/platform/mvnw" -f "$repo/platform/pom.xml" -B -ntp -q clean package \
        -DskipTests -Ddocker.skip=true -pl services/iam -am
fi

step "首次部署"
started=$(date +%s)
compose up -d --build --wait --quiet-pull > /dev/null 2>&1 || { compose ps -a; compose logs --tail 80; fail "首次部署未全部健康"; }
first_start=$(($(date +%s) - started))
published=$(compose ps --format '{{.Ports}}' | grep -c -- '->' || true)
[ "$published" = "1" ] || fail "应只有 nginx 发布主机端口"

step "初始化管理员"
# 密码经标准输入写入容器内仅属服务用户的临时文件，不出现在命令参数中。
init_out=$(printf '%s' "$admin_pw" | compose run --rm -T --entrypoint sh iam -c \
    'umask 077; cat > /tmp/admin-password; exec java -jar /app/iam.jar initialize-admin --login-name=admin --password-file=/tmp/admin-password' \
    2> "$work/init.log") || { cat "$work/init.log"; fail "管理员初始化"; }
grep -q administrator_initialized <<< "$init_out" || fail "管理员初始化未确认"
admin_token=$(token admin "$admin_pw")

step "写入数据并验证入口边界"
register alice "$alice_pw" "$admin_token"
[ "$(login alice "$alice_pw")" = "200" ] || fail "alice 登录"
[ "$(get_status /internal/access/current "$admin_token")" = "404" ] || fail "/internal/ 不应对外"
[ "$(get_status /actuator/health "$admin_token")" = "404" ] || fail "管理端点不应对外"

step "普通重启保留数据"
compose down > /dev/null 2>&1
compose up -d --wait > /dev/null 2>&1 || fail "重启未全部健康"
[ "$(login alice "$alice_pw")" = "200" ] || fail "重启后 alice 登录"
admin_token=$(token admin "$admin_pw")

step "备份"
backup_line=$("$repo/deploy/backup.sh" "$env_file" "$work/backups" 2> "$work/backup.log") \
    || { cat "$work/backup.log"; fail "备份脚本"; }
dump=$(sed -n 's/^backup=\([^ ]*\) .*/\1/p' <<< "$backup_line")
[ -s "$dump" ] || fail "备份文件"

step "备份后继续变更"
register bob "$bob_pw" "$admin_token"
alice_token=$(token alice "$alice_pw")
post /api/auth/password "{\"oldPassword\":\"$alice_pw\",\"newPassword\":\"$alice_new_pw\"}" "$alice_token" > /dev/null
expect 200 "alice 改密"
[ "$(login alice "$alice_new_pw")" = "200" ] || fail "alice 新密码登录"
stale_token=$(token admin "$admin_pw")

step "删除数据库卷模拟数据丢失"
compose down > /dev/null 2>&1
docker volume rm "${COMPOSE_PROJECT_NAME}_postgres-data" > /dev/null
compose up -d --wait > /dev/null 2>&1 || fail "空库启动未全部健康"
[ "$(login admin "$admin_pw")" = "401" ] || fail "空库中不应存在管理员"

step "恢复"
restore_line=$("$repo/deploy/restore.sh" "$env_file" "$dump" --yes 2> "$work/restore.log" | tail -1) \
    || { cat "$work/restore.log"; fail "恢复脚本"; }

step "核对恢复结果"
[ "$(login admin "$admin_pw")" = "200" ] || fail "恢复后管理员登录"
[ "$(login alice "$alice_pw")" = "200" ] || fail "恢复后 alice 应回到备份时的密码"
[ "$(login alice "$alice_new_pw")" = "401" ] || fail "备份后的改密不应保留"
[ "$(login bob "$bob_pw")" = "401" ] || fail "备份后注册的 bob 不应存在"
[ "$(get_status /api/me "$stale_token")" = "401" ] || fail "恢复前的会话应失效"
admin_token=$(token admin "$admin_pw")
[ "$(get_status /api/audit "$admin_token")" = "200" ] || fail "恢复后审计查询"

echo "first_start_seconds=$first_start"
echo "$backup_line" | sed "s#$work/##"
echo "$restore_line" | sed "s#$work/##"
echo "deploy_check=passed"
