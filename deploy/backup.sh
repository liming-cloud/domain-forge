#!/usr/bin/env bash
# PostgreSQL 在线逻辑备份：pg_dump 自定义格式，取自单一快照，备份期间服务不停。
# 覆盖 domainforge 库中 IAM 与各业务服务的 schema，并保留对象属主。
# 生成 domainforge-<UTC 时间>.dump 与同名 .sha256；写完并通过可读性校验后才改为正式文件名。
# 同时导出 Nacos 中的全部配置为 domainforge-<UTC 时间>.nacos.json（含控制台修改），供 restore.sh 一并恢复。
# 实例中其他业务数据库（服务的附加数据源，如归档库）各自导出为 domainforge-<UTC 时间>.db.<库名>.dump。
# 使用文件存储的服务，其文件卷打包为 domainforge-<UTC 时间>.files.<服务名>.tar（在数据库备份之后打包：
# 期间新上传的文件只会多出没有记录的对象，由清理任务删除；删除的文件保留 24 小时后才删除对象，因此备份须在 24 小时内完成）。
# 备份含密码摘要与敏感输入指纹，目录只允许当前用户访问；异地保存与加密由运维负责。
#
# 用法：deploy/backup.sh <环境文件> <备份目录>
# 指定 Compose 项目名时设置 COMPOSE_PROJECT_NAME；部署了业务服务时设置 COMPOSE_FILE。
set -euo pipefail

if [ $# -ne 2 ]; then
    echo "用法：$0 <环境文件> <备份目录>" >&2
    exit 2
fi
deploy="$(cd "$(dirname "$0")" && pwd)"
env_file=$1
out=$2
# 未设置 COMPOSE_FILE 时只用 deploy/compose.yml；部署了业务服务时把其覆盖文件一并列入（以 : 分隔）。
compose() { COMPOSE_FILE="${COMPOSE_FILE:-$deploy/compose.yml}" docker compose --env-file "$env_file" "$@"; }
checksum() { if command -v sha256sum > /dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
# 实例中除 domainforge 以外的业务数据库（如服务的附加数据源：归档库），各自单独备份恢复。
extra_databases() {
    compose exec -T postgres psql -U domainforge -d domainforge -tAc \
        "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname NOT IN ('postgres', 'domainforge') ORDER BY 1"
}
# 使用文件存储的服务：挂载了 /var/lib/domain-forge/files 的服务（每个服务一个命名卷）。
file_services() {
    compose config --format json | python3 -I -c '
import json, sys
for name, service in sorted(json.load(sys.stdin)["services"].items()):
    if any(v.get("target") == "/var/lib/domain-forge/files" for v in service.get("volumes", [])):
        print(name)'
}

umask 077
mkdir -p "$out"
name="domainforge-$(date -u +%Y%m%dT%H%M%SZ).dump"
partial="$out/.$name.partial"
configs="${name%.dump}.nacos.json"
trap 'rm -f "$partial" "$out/.$configs.partial" "$out"/.*.tar.partial "$out"/.*.dump.partial' EXIT

started=$(date +%s)
compose exec -T postgres pg_dump -U domainforge -d domainforge --format=custom > "$partial"
compose exec -T postgres pg_restore --list < "$partial" > /dev/null
mv "$partial" "$out/$name"
(cd "$out" && checksum "$name" > "$name.sha256")
for database in $(extra_databases); do
    extra="${name%.dump}.db.$database.dump"
    compose exec -T postgres pg_dump -U domainforge -d "$database" --format=custom > "$out/.$extra.partial"
    compose exec -T postgres pg_restore --list < "$out/.$extra.partial" > /dev/null
    mv "$out/.$extra.partial" "$out/$extra"
    (cd "$out" && checksum "$extra" > "$extra.sha256")
done
for service in $(file_services); do
    files="${name%.dump}.files.$service.tar"
    # 用服务镜像起一个临时容器读取卷（服务是否在运行都可以），跳过写入中的 .incoming。
    compose run --rm --no-deps -T --entrypoint tar "$service" \
        -C /var/lib/domain-forge/files --exclude=./.incoming -cf - . > "$out/.$files.partial"
    tar -tf "$out/.$files.partial" > /dev/null
    mv "$out/.$files.partial" "$out/$files"
    (cd "$out" && checksum "$files" > "$files.sha256")
done
python3 -I "$deploy/nacos/configs.py" export "$env_file" "$out/.$configs.partial" > /dev/null
mv "$out/.$configs.partial" "$out/$configs"
(cd "$out" && checksum "$configs" > "$configs.sha256")

echo "backup=$out/$name bytes=$(wc -c < "$out/$name" | tr -d ' ') seconds=$(($(date +%s) - started))"
