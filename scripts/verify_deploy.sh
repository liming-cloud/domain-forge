#!/usr/bin/env bash
# 部署参考的真实场景验证：用独立 Compose 项目和临时密钥启动整套服务，依次验证
#   首次部署与管理员初始化 → 普通重启保留数据 → 备份 → 备份后继续变更 →
#   删除数据库卷模拟数据丢失 → 恢复 → 数据回到备份时刻、旧会话全部失效 →
#   配置中心（种子首次发布、运行中发布生效、重新部署不覆盖、删除 PostgreSQL 与 Nacos 卷后连同配置恢复）→
#   升级到带新增迁移的版本 → 应用回退 → 迁移失败的升级及恢复 → 用升级前备份做数据回退。
# 新版本镜像在当前镜像上叠加验证专用迁移来模拟，不修改源码。
# 结果以接口行为判定，不以容器启动或备份文件存在代替；结束时删除本次创建的容器、卷和临时文件。
#
# 用法：scripts/verify_deploy.sh            # 先 clean package 构建 IAM jar
#       SKIP_BUILD=1 scripts/verify_deploy.sh  # 复用已构建的 jar
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
export COMPOSE_PROJECT_NAME="dfverify$$"
# shellcheck source=scripts/deploy_lib.sh
. "$repo/scripts/deploy_lib.sh"
port=$(free_port)
base="http://127.0.0.1:$port"
env_file="$work/df.env"
tag_v1="verify$$-v1"
tag_v2="verify$$-v2"
tag_v3="verify$$-v3"
compose() { docker compose -f "$repo/deploy/compose.yml" --env-file "$env_file" "$@"; }
cleanup() {
    compose down -v --remove-orphans > /dev/null 2>&1 || true
    docker image rm -f "domain-forge/iam:$tag_v3" "domain-forge/iam:$tag_v2" "domain-forge/iam:$tag_v1" \
        > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

set_tag() {
    sed "s/^IAM_IMAGE_TAG=.*/IAM_IMAGE_TAG=$1/" "$env_file" > "$env_file.next"
    chmod 600 "$env_file.next"
    mv "$env_file.next" "$env_file"
}

write_env "$port" "IAM_IMAGE_TAG=$tag_v1"
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
initialize_admin "$admin_pw"
admin_token=$(token admin "$admin_pw")

step "写入数据并验证入口边界"
register alice "$alice_pw" "$admin_token" > /dev/null
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
register bob "$bob_pw" "$admin_token" > /dev/null
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

step "配置中心：种子已发布并被 IAM 读取"
configs() { python3 -I "$repo/deploy/nacos/configs.py" "$@"; }
configs export "$env_file" "$work/configs.json" > /dev/null
grep -q 'source: nacos' "$work/configs.json" || fail "iam.yaml 种子未发布"

# publish_attempts <次数>：以管理员身份发布 iam.yaml，只改账号失败次数阈值
publish_attempts() {
    python3 -I -c 'import json, sys; print(json.dumps([{"dataId": "iam.yaml", "group": "DEFAULT_GROUP", "type": "yaml",
        "content": "domainforge:\n  config:\n    source: nacos\niam:\n  login-protection:\n"
                   "    account-attempts: " + sys.argv[1] + "\n    account-window-seconds: 600\n"
                   "    address-attempts: 30\n    address-window-seconds: 60\n"}]))' "$1" > "$work/publish.json"
    configs import "$env_file" "$work/publish.json" > /dev/null
}
# limited_at：同一新账号名连续以错误密码登录，返回第几次得到 429
limited_at() {
    local name="cfg$RANDOM$RANDOM" attempt
    for attempt in 1 2 3 4 5 6; do
        [ "$(login "$name" "Wrong-$(secret)")" = "429" ] && { echo "$attempt"; return; }
    done
    echo 0
}
# await_limited <次数>：等待阈值在 IAM 中实际生效（以入口的登录结果为准）
await_limited() {
    local attempt
    for attempt in $(seq 1 40); do
        [ "$(limited_at)" = "$1" ] && return
        sleep 0.5
    done
    fail "配置未在 IAM 中生效：期望第 $1 次登录被限流"
}

step "配置中心：运行中发布生效"
publish_attempts 2
await_limited 3

step "配置中心：重新部署不覆盖控制台修改"
compose down > /dev/null 2>&1
compose up -d --wait > /dev/null 2>&1 || fail "重新部署未全部健康"
grep -q 'nacos config kept: iam.yaml' <<< "$(compose logs nacos-init)" || fail "重新部署时应保留已有配置"
await_limited 3

step "配置中心：删除 PostgreSQL 与 Nacos 卷后连同配置恢复"
config_backup=$("$repo/deploy/backup.sh" "$env_file" "$work/config-backups" 2> "$work/config-backup.log") \
    || { cat "$work/config-backup.log"; fail "备份脚本"; }
config_dump=$(sed -n 's/^backup=\([^ ]*\) .*/\1/p' <<< "$config_backup")
[ -s "${config_dump%.dump}.nacos.json" ] || fail "备份应包含 Nacos 配置"
publish_attempts 4
await_limited 5
compose down > /dev/null 2>&1
docker volume rm "${COMPOSE_PROJECT_NAME}_postgres-data" "${COMPOSE_PROJECT_NAME}_nacos-data" > /dev/null
compose up -d --wait > /dev/null 2>&1 || fail "空卷启动未全部健康"
grep -q 'nacos config seeded: iam.yaml' <<< "$(compose logs nacos-init)" || fail "空 Nacos 应重新发布种子"
"$repo/deploy/restore.sh" "$env_file" "$config_dump" --yes > /dev/null 2> "$work/config-restore.log" \
    || { cat "$work/config-restore.log"; fail "恢复脚本"; }
await_limited 3
[ "$(login alice "$alice_pw")" = "200" ] || fail "连同配置恢复后 alice 登录"

step "构建模拟新版本镜像"
mkdir -p "$work/v2" "$work/v3"
cat > "$work/v2/V9000__deploy_upgrade_probe.sql" <<'EOF'
-- 验证专用：向后兼容的迁移，只新增表，旧版本应用可以继续运行。
CREATE TABLE deploy_upgrade_probe (id bigint PRIMARY KEY);
EOF
cat > "$work/v2/Dockerfile" <<EOF
FROM domain-forge/iam:$tag_v1
COPY V9000__deploy_upgrade_probe.sql /app/migration/
ENV SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:/app/migration
EOF
cat > "$work/v3/V9001__deploy_upgrade_broken.sql" <<'EOF'
-- 验证专用：执行到第二条语句失败，整个迁移应在事务内回滚。
CREATE TABLE deploy_upgrade_broken (id bigint PRIMARY KEY);
SELECT missing_column FROM deploy_upgrade_broken;
EOF
cat > "$work/v3/Dockerfile" <<EOF
FROM domain-forge/iam:$tag_v2
COPY V9001__deploy_upgrade_broken.sql /app/migration/
EOF
docker build -q -t "domain-forge/iam:$tag_v2" "$work/v2" > /dev/null
docker build -q -t "domain-forge/iam:$tag_v3" "$work/v3" > /dev/null

step "升级到新增迁移的版本"
set_tag "$tag_v2"
"$repo/deploy/upgrade.sh" "$env_file" "$work/upgrade-backups" > "$work/upgrade.out" 2> "$work/upgrade.log" \
    || { cat "$work/upgrade.log"; fail "升级脚本"; }
pre_upgrade_dump=$(sed -n 's/^backup=\([^ ]*\) .*/\1/p' "$work/upgrade.out")
[ -s "$pre_upgrade_dump" ] || fail "升级前应自动备份"
[ "$(sql "select count(*) from flyway_schema_history where version = '9000' and success")" = "1" ] \
    || fail "新版本迁移应已执行"
[ "$(login alice "$alice_pw")" = "200" ] || fail "升级后 alice 登录"

step "应用回退（保留新增迁移）"
set_tag "$tag_v1"
compose up -d --no-build --wait iam nginx > /dev/null 2>&1 || fail "应用回退后未健康"
[ "$(login alice "$alice_pw")" = "200" ] || fail "应用回退后 alice 登录"
[ "$(sql "select to_regclass('deploy_upgrade_probe') is not null")" = "t" ] || fail "应用回退不应改动数据库"

step "迁移失败的升级"
set_tag "$tag_v3"
started=$(date +%s)
if "$repo/deploy/upgrade.sh" "$env_file" "$work/upgrade-backups" > /dev/null 2>&1; then
    fail "迁移失败时升级脚本应以非零退出"
fi
failed_upgrade=$(($(date +%s) - started))
[ "$(sql "select count(*) from flyway_schema_history where version = '9001'")" = "0" ] || fail "失败迁移不应留下记录"
[ "$(sql "select to_regclass('deploy_upgrade_broken') is null")" = "t" ] || fail "失败迁移应整体回滚"
set_tag "$tag_v2"
compose up -d --no-build --wait iam nginx > /dev/null 2>&1 || fail "失败后回到上一版本未健康"
[ "$(login alice "$alice_pw")" = "200" ] || fail "失败后回到上一版本 alice 登录"

step "数据回退：恢复升级前备份并回到旧版本"
set_tag "$tag_v1"
rollback_line=$("$repo/deploy/restore.sh" "$env_file" "$pre_upgrade_dump" --yes 2> "$work/rollback.log" | tail -1) \
    || { cat "$work/rollback.log"; fail "数据回退"; }
[ "$(sql "select count(*) from flyway_schema_history where version = '9000'")" = "0" ] || fail "数据回退后不应有新版本迁移"
[ "$(sql "select to_regclass('deploy_upgrade_probe') is null")" = "t" ] || fail "数据回退后不应有新版本的表"
[ "$(login alice "$alice_pw")" = "200" ] || fail "数据回退后 alice 登录"

echo "first_start_seconds=$first_start"
echo "$backup_line" | sed "s#$work/##"
echo "$restore_line" | sed "s#$work/##"
grep '^upgrade=' "$work/upgrade.out"
echo "failed_upgrade_seconds=$failed_upgrade"
echo "$rollback_line" | sed "s#$work/##; s/^restored=/data_rollback=/"
echo "deploy_check=passed"
