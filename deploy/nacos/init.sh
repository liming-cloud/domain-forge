#!/bin/sh
# 一次性初始化 Nacos 鉴权，可重复执行：
# 1. 首次启动时设置管理员 nacos 的密码（已设置则跳过，此后只能在控制台修改）；
# 2. 创建服务专用账号，只授予指定命名空间的服务注册与发现读写、配置只读权限；
# 3. 发布 /init/config 下的配置种子：dataId 不存在时才发布，之后以 Nacos 中的内容为准，重新部署不覆盖。
# 已存在的服务账号不会被改密；改密后需在 Nacos 控制台同步修改。
set -eu

server="http://${NACOS_HOST:-nacos}:8848/nacos/v3"
base="$server/auth"
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
curl -sS -f -o /dev/null -H "accessToken: $token" -X POST "$base/permission" \
    --data-urlencode "role=$role" --data-urlencode "resource=$namespace:*:config/*" --data-urlencode "action=r"

verify=$(curl -sS -X POST "$base/user/login" \
    --data-urlencode "username=$NACOS_SERVICE_USERNAME" --data-urlencode "password=$NACOS_SERVICE_PASSWORD")
case "$verify" in
    *'"accessToken"'*) echo "nacos service account ready: $NACOS_SERVICE_USERNAME ($namespace naming rw, config r)" ;;
    *) echo "nacos service account login failed: password differs from the existing account" >&2; exit 1 ;;
esac

for seed in /init/config/*.yaml; do
    [ -e "$seed" ] || continue
    data_id=$(basename "$seed")
    query="dataId=$data_id&groupName=DEFAULT_GROUP&namespaceId=$namespace"
    status=$(curl -sS -o /dev/null -w '%{http_code}' -H "accessToken: $token" "$server/admin/cs/config?$query")
    if [ "$status" = "404" ]; then
        curl -sS -f -o /dev/null -H "accessToken: $token" -X POST "$server/admin/cs/config" \
            --data-urlencode "dataId=$data_id" --data-urlencode "groupName=DEFAULT_GROUP" \
            --data-urlencode "namespaceId=$namespace" --data-urlencode "type=yaml" \
            --data-urlencode "content@$seed"
        echo "nacos config seeded: $data_id"
    elif [ "$status" = "200" ]; then
        echo "nacos config kept: $data_id"
    else
        echo "nacos config check failed: $data_id (HTTP $status)" >&2
        exit 1
    fi
    # 发布后客户端读取路径要数百毫秒才返回新内容；等到可读再退出，避免紧随其后启动的服务读到空配置。
    for attempt in $(seq 1 50); do
        if curl -sS -H "accessToken: $token" "$server/client/cs/config?$query" | grep -q '"content":"'; then
            break
        fi
        if [ "$attempt" = "50" ]; then
            echo "nacos config not readable: $data_id" >&2
            exit 1
        fi
        sleep 0.2
    done
done
