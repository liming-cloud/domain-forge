#!/bin/sh
# 一次性初始化 Nacos 鉴权，可重复执行：
# 1. 首次启动时设置管理员 nacos 的密码（已设置则跳过，此后只能在控制台修改）；
# 2. 创建服务专用账号，只授予指定命名空间的服务注册与发现读写权限。
# 已存在的服务账号不会被改密；改密后需在 Nacos 控制台同步修改。
set -eu

base="http://${NACOS_HOST:-nacos}:8848/nacos/v3/auth"
namespace="${NACOS_NAMESPACE:-public}"
role=domainforge-service

admin=$(curl -sS -X POST "$base/user/admin" --data-urlencode "password=$NACOS_ADMIN_PASSWORD")
case "$admin" in
    *'"code":0'*) echo "nacos admin password initialized" ;;
    *'"code":409'*) echo "nacos admin already initialized" ;;
    *) echo "nacos admin initialization failed" >&2; exit 1 ;;
esac

login=$(curl -sS -X POST "$base/user/login" \
    --data-urlencode "username=nacos" --data-urlencode "password=$NACOS_ADMIN_PASSWORD")
token=$(printf '%s' "$login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [ -z "$token" ]; then
    echo "nacos admin login failed: NACOS_ADMIN_PASSWORD does not match the initialized password" >&2
    exit 1
fi

curl -sS -f -o /dev/null -H "accessToken: $token" -X POST "$base/user" \
    --data-urlencode "username=$NACOS_SERVICE_USERNAME" --data-urlencode "password=$NACOS_SERVICE_PASSWORD"
curl -sS -f -o /dev/null -H "accessToken: $token" -X POST "$base/role" \
    --data-urlencode "role=$role" --data-urlencode "username=$NACOS_SERVICE_USERNAME"
curl -sS -f -o /dev/null -H "accessToken: $token" -X POST "$base/permission" \
    --data-urlencode "role=$role" --data-urlencode "resource=$namespace:*:naming/*" --data-urlencode "action=rw"

verify=$(curl -sS -X POST "$base/user/login" \
    --data-urlencode "username=$NACOS_SERVICE_USERNAME" --data-urlencode "password=$NACOS_SERVICE_PASSWORD")
case "$verify" in
    *'"accessToken"'*) echo "nacos service account ready: $NACOS_SERVICE_USERNAME ($namespace naming rw)" ;;
    *) echo "nacos service account login failed: password differs from the existing account" >&2; exit 1 ;;
esac
