#!/usr/bin/env bash
# 升级 IAM 到环境文件中 IAM_IMAGE_TAG 指定的镜像：先备份，再替换 iam 并等待健康。
# 镜像需事先构建好（见 deploy/README.md），本脚本不构建。iam 替换期间入口返回 502。
# 失败时脚本以非零退出并保留现场，按 README 的回退条件选择应用回退或数据恢复。
#
# 用法：deploy/upgrade.sh <环境文件> <备份目录>
# 指定 Compose 项目名时设置 COMPOSE_PROJECT_NAME；部署了业务服务时设置 COMPOSE_FILE。
set -euo pipefail

if [ $# -ne 2 ]; then
    echo "用法：$0 <环境文件> <备份目录>" >&2
    exit 2
fi
deploy="$(cd "$(dirname "$0")" && pwd)"
env_file=$1
backups=$2
# 未设置 COMPOSE_FILE 时只用 deploy/compose.yml；部署了业务服务时把其覆盖文件一并列入（以 : 分隔）。
compose() { COMPOSE_FILE="${COMPOSE_FILE:-$deploy/compose.yml}" docker compose --env-file "$env_file" "$@"; }

image=$(compose config --format json | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["services"]["iam"]["image"])')
if ! docker image inspect "$image" > /dev/null 2>&1; then
    echo "镜像 $image 不存在，先构建再升级" >&2
    exit 1
fi
running=$(docker inspect --format '{{.Config.Image}}' "$(compose ps -q iam)" 2> /dev/null || echo "无")

backup_line=$("$deploy/backup.sh" "$env_file" "$backups")
echo "$backup_line"

started=$(date +%s)
if ! compose up -d --no-build --wait --wait-timeout 180 iam nginx; then
    echo "upgrade=failed from=$running to=$image seconds=$(($(date +%s) - started))" >&2
    echo "升级前备份：${backup_line#backup=}" >&2
    exit 1
fi
echo "upgrade=done from=$running to=$image seconds=$(($(date +%s) - started))"
