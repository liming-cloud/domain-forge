#!/bin/sh
# 一次性初始化 PowerJob：等服务端就绪，以管理员登录，创建命名空间 domainforge，按 apps.conf 注册应用。
# 可重复执行：已存在的命名空间与应用保持不变（不改密码；改密在控制台进行并同步环境文件）。
set -eu

server="http://${POWERJOB_HOST:-powerjob-server}:7700"
post() { curl -sS -X POST "$server$1" -H 'Content-Type: application/json' -H "Power_jwt: ${token:-}" -d "$2"; }

for attempt in $(seq 1 90); do
    login=$(curl -sS -X POST "$server/auth/thirdPartyLoginDirect" -H 'Content-Type: application/json' \
        -d "{\"loginType\":\"PWJB\",\"originParams\":\"{\\\"username\\\":\\\"ADMIN\\\",\\\"password\\\":\\\"$POWERJOB_ADMIN_PASSWORD\\\"}\"}" 2>/dev/null || true)
    token=$(printf '%s' "$login" | sed -n 's/.*"jwtToken":"\([^"]*\)".*/\1/p')
    [ -n "$token" ] && break
    case "$login" in *'"success":false'*) echo "powerjob admin login failed: POWERJOB_ADMIN_PASSWORD does not match" >&2; exit 1 ;; esac
    [ "$attempt" = "90" ] && { echo "powerjob server not ready" >&2; exit 1; }
    sleep 2
done

namespace=$(post /namespace/save '{"code":"domainforge","name":"domainforge"}')
case "$namespace" in
    *'"success":true'*) echo "powerjob namespace created: domainforge" ;;
    *exist*) echo "powerjob namespace kept: domainforge" ;;
    *) echo "powerjob namespace failed: $namespace" >&2; exit 1 ;;
esac

while read -r app; do
    case "$app" in ''|'#'*) continue ;; esac
    result=$(post /appInfo/save "{\"appName\":\"$app\",\"password\":\"$POWERJOB_APP_PASSWORD\",\"namespaceCode\":\"domainforge\"}")
    case "$result" in
        *'"success":true'*) echo "powerjob app registered: $app" ;;
        *'already exists'*) echo "powerjob app kept: $app" ;;
        *) echo "powerjob app failed: $app" >&2; exit 1 ;;
    esac
done < /init/apps.conf
echo "powerjob init done"
