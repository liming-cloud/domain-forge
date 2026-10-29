#!/usr/bin/env bash
# 从 backup.sh 生成的备份恢复 PostgreSQL。会删除当前库的全部数据，需显式传入 --yes。
# 过程：校验摘要 → 停止全部应用服务与入口 → 由各业务服务的 db-init 建好数据库账号 →
#       重建 domainforge 库并在单一事务内导入（保留对象属主）→
#       用同名 .db.<库名>.dump 重建其他业务数据库（保留属主；备份中没有的库保持不变）→
#       用同名 .files.<服务名>.tar 替换各服务文件卷的内容（备份中没有时保留现有文件并提示）→
#       重启 Redis（不持久化，清空全部会话与登录防护计数）→ 有同名 .nacos.json 时恢复 Nacos 配置 →
#       启动全部服务并等待健康。Nacos 中存在而备份中没有的配置保持不变。
# 恢复后备份时刻之后的变更全部丢失，所有用户需重新登录。必须使用备份时的同一份环境文件
# （尤其是 IAM_FINGERPRINT_KEY），否则依赖指纹的数据无法再匹配。
#
# 用法：deploy/restore.sh <环境文件> <备份文件> --yes
# 指定 Compose 项目名时设置 COMPOSE_PROJECT_NAME；部署了业务服务时设置 COMPOSE_FILE。
set -euo pipefail

if [ $# -ne 3 ] || [ "$3" != "--yes" ]; then
    echo "用法：$0 <环境文件> <备份文件> --yes（将删除当前库的全部数据）" >&2
    exit 2
fi
deploy="$(cd "$(dirname "$0")" && pwd)"
env_file=$1
dump=$2
# 未设置 COMPOSE_FILE 时只用 deploy/compose.yml；部署了业务服务时把其覆盖文件一并列入（以 : 分隔）。
compose() { COMPOSE_FILE="${COMPOSE_FILE:-$deploy/compose.yml}" docker compose --env-file "$env_file" "$@"; }
checksum() { if command -v sha256sum > /dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
# 使用文件存储的服务：挂载了 /var/lib/domain-forge/files 的服务（每个服务一个命名卷）。
file_services() {
    compose config --format json | python3 -I -c '
import json, sys
for name, service in sorted(json.load(sys.stdin)["services"].items()):
    if any(v.get("target") == "/var/lib/domain-forge/files" for v in service.get("volumes", [])):
        print(name)'
}

if [ ! -f "$dump.sha256" ]; then
    echo "缺少摘要文件 $dump.sha256，拒绝恢复" >&2
    exit 1
fi
(cd "$(dirname "$dump")" && checksum -c "$(basename "$dump").sha256" > /dev/null)
configs="${dump%.dump}.nacos.json"
if [ -f "$configs" ]; then
    (cd "$(dirname "$configs")" && checksum -c "$(basename "$configs").sha256" > /dev/null)
fi
for extra in "${dump%.dump}".db.*.dump; do
    [ -f "$extra" ] || continue
    (cd "$(dirname "$extra")" && checksum -c "$(basename "$extra").sha256" > /dev/null)
done
for service in $(file_services); do
    files="${dump%.dump}.files.$service.tar"
    if [ -f "$files" ]; then
        (cd "$(dirname "$files")" && checksum -c "$(basename "$files").sha256" > /dev/null)
    fi
done

started=$(date +%s)
services=$(compose config --services)
# shellcheck disable=SC2046 # 服务名不含空白，按词拆分
compose stop $(grep -v -x -e postgres -e redis -e nacos -e nacos-init <<< "$services")
compose up -d --wait postgres redis
compose exec -T postgres pg_restore --list < "$dump" > /dev/null
# 业务服务的数据库账号是实例级对象，不在备份中；先建好，导入时才能还原其 schema 的属主。
for init in $(grep -e '-db-init$' <<< "$services" || true); do
    compose run --rm --no-deps "$init" > /dev/null
done
compose exec -T postgres dropdb -U domainforge --force --if-exists domainforge
compose exec -T postgres createdb -U domainforge domainforge
compose exec -T postgres pg_restore -U domainforge -d domainforge --exit-on-error --single-transaction < "$dump"
# 其他业务数据库：db-init 已按环境文件建好库与属主账号；记下属主后重建为空库再导入。
for extra in "${dump%.dump}".db.*.dump; do
    [ -f "$extra" ] || continue
    database="${extra##*.db.}"
    database="${database%.dump}"
    owner=$(compose exec -T postgres psql -U domainforge -d domainforge -tAc \
        "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = '$database'")
    compose exec -T postgres dropdb -U domainforge --force --if-exists "$database"
    compose exec -T postgres createdb -U domainforge -O "${owner:-domainforge}" "$database"
    compose exec -T postgres pg_restore -U domainforge -d "$database" --exit-on-error --single-transaction < "$extra"
done
for service in $(file_services); do
    files="${dump%.dump}.files.$service.tar"
    if [ ! -f "$files" ]; then
        echo "警告：备份中没有 $service 的文件（$files），保留其现有文件" >&2
        continue
    fi
    compose run --rm --no-deps -T --entrypoint sh "$service" -c \
        'find /var/lib/domain-forge/files -mindepth 1 -delete && tar -C /var/lib/domain-forge/files -xf -' < "$files"
done
compose restart redis
if [ -f "$configs" ]; then
    compose up -d --wait nacos
    python3 -I "$deploy/nacos/configs.py" import "$env_file" "$configs" > /dev/null
fi
compose up -d --wait

echo "restored=$dump seconds=$(($(date +%s) - started))"
