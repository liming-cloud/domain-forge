#!/usr/bin/env bash
# 更换 HTTPS 证书：先在主机上检查 NGINX_TLS_DIR 中的新证书，通过后让 Nginx 平滑重载（不断开现有连接）。
# 检查项：fullchain.pem 与 privkey.pem 都存在、私钥与证书匹配、证书当前有效；30 天内到期时警告。
# 任何一项不通过都不重载，Nginx 继续使用当前证书。也可以只做检查（--check），用于定时的到期巡检。
#
# 用法：deploy/tls-reload.sh <环境文件> [--check]
# COMPOSE_FILE 须包含 compose.https.yml，例如 deploy/compose.yml:deploy/compose.https.yml（另有业务服务覆盖文件时一并列入）。
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ] || { [ $# -eq 2 ] && [ "$2" != "--check" ]; }; then
    echo "用法：$0 <环境文件> [--check]" >&2
    exit 2
fi
deploy="$(cd "$(dirname "$0")" && pwd)"
env_file=$1
compose() {
    COMPOSE_FILE="${COMPOSE_FILE:-$deploy/compose.yml:$deploy/compose.https.yml}" docker compose --env-file "$env_file" "$@"
}

dir=$(sed -n 's/^NGINX_TLS_DIR=//p' "$env_file" | tail -1)
[ -n "$dir" ] || { echo "tls_check=failed: 环境文件中没有 NGINX_TLS_DIR" >&2; exit 1; }
cert="$dir/fullchain.pem"
key="$dir/privkey.pem"
[ -r "$cert" ] && [ -r "$key" ] || { echo "tls_check=failed: 缺少 $cert 或 $key" >&2; exit 1; }

cert_key=$(openssl x509 -in "$cert" -noout -pubkey 2> /dev/null) \
    || { echo "tls_check=failed: fullchain.pem 不是有效证书" >&2; exit 1; }
private_key=$(openssl pkey -in "$key" -pubout 2> /dev/null) \
    || { echo "tls_check=failed: privkey.pem 不是有效私钥" >&2; exit 1; }
[ "$cert_key" = "$private_key" ] || { echo "tls_check=failed: 私钥与证书不匹配" >&2; exit 1; }
openssl x509 -in "$cert" -noout -checkend 0 > /dev/null \
    || { echo "tls_check=failed: 证书已过期" >&2; exit 1; }
not_after=$(openssl x509 -in "$cert" -noout -enddate | cut -d= -f2)
if ! openssl x509 -in "$cert" -noout -checkend 2592000 > /dev/null; then
    echo "tls_check=warning: 证书将在 30 天内到期（$not_after），请尽快更换" >&2
fi

if [ "${2:-}" = "--check" ]; then
    echo "tls_check=passed not_after=$not_after"
    exit 0
fi
compose exec -T nginx nginx -t > /dev/null 2>&1 || { echo "tls_reload=failed: Nginx 配置检查未通过" >&2; exit 1; }
compose exec -T nginx nginx -s reload > /dev/null
echo "tls_reload=done not_after=$not_after"
