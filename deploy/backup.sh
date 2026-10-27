#!/usr/bin/env bash
# PostgreSQL 在线逻辑备份：pg_dump 自定义格式，取自单一快照，备份期间服务不停。
# 生成 domainforge-<UTC 时间>.dump 与同名 .sha256；写完并通过可读性校验后才改为正式文件名。
# 备份含密码摘要与敏感输入指纹，目录只允许当前用户访问；异地保存与加密由运维负责。
#
# 用法：deploy/backup.sh <环境文件> <备份目录>
# 指定 Compose 项目名时设置 COMPOSE_PROJECT_NAME。
set -euo pipefail

if [ $# -ne 2 ]; then
    echo "用法：$0 <环境文件> <备份目录>" >&2
    exit 2
fi
deploy="$(cd "$(dirname "$0")" && pwd)"
env_file=$1
out=$2
compose() { docker compose -f "$deploy/compose.yml" --env-file "$env_file" "$@"; }
checksum() { if command -v sha256sum > /dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

umask 077
mkdir -p "$out"
name="domainforge-$(date -u +%Y%m%dT%H%M%SZ).dump"
partial="$out/.$name.partial"
trap 'rm -f "$partial"' EXIT

started=$(date +%s)
compose exec -T postgres pg_dump -U domainforge -d domainforge --format=custom --no-owner > "$partial"
compose exec -T postgres pg_restore --list < "$partial" > /dev/null
mv "$partial" "$out/$name"
(cd "$out" && checksum "$name" > "$name.sha256")

echo "backup=$out/$name bytes=$(wc -c < "$out/$name" | tr -d ' ') seconds=$(($(date +%s) - started))"
