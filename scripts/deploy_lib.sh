# 部署验证脚本共用的函数，由 verify_deploy.sh、verify_closure.sh 引入，不单独执行。
# 引入前需设置：work（临时目录）、base（入口地址）、env_file（环境文件），并定义 compose 函数。

step() { echo "== $*"; }
fail() { echo "deploy_check=failed: $*" >&2; exit 1; }
secret() { python3 -I -c 'import secrets; print(secrets.token_hex(24))'; }
key() { python3 -I -c 'import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())'; }
free_port() { python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
field() { python3 -I -c 'import json, sys; print(json.load(sys.stdin)["data"][sys.argv[1]])' "$1"; }
size() { python3 -I -c 'import json, sys; print(len(json.load(sys.stdin)["data"]))'; }
sql() { compose exec -T postgres psql -U domainforge -d domainforge -tAc "$1"; }

# write_env <入口端口> [额外行...]：生成带随机密钥的环境文件
write_env() {
    local port=$1
    shift
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
    printf '%s\n' "$@" >> "$env_file"
    chmod 600 "$env_file"
}

# call <方法> <路径> [JSON] [令牌]：输出响应体，HTTP 状态写入 $work/status
call() {
    local args=(-X "$1" "$base$2")
    [ $# -ge 3 ] && [ -n "$3" ] && args+=(-H 'Content-Type: application/json' -d "$3")
    [ $# -ge 4 ] && args+=(-H "Authorization: Bearer $4")
    curl -sS -o "$work/body" -w '%{http_code}' "${args[@]}" > "$work/status"
    cat "$work/body"
}
post() { call POST "$1" "$2" "${3:-}"; }
put() { call PUT "$1" "$2" "${3:-}"; }
get() { call GET "$1" "" "${2:-}"; }
get_status() { get "$1" "$2" > /dev/null; cat "$work/status"; }
expect() { [ "$(cat "$work/status")" = "$1" ] || fail "$2：期望 $1，实际 $(cat "$work/status") $(cat "$work/body")"; }
login() { post /api/auth/login "{\"loginName\":\"$1\",\"password\":\"$2\"}" > /dev/null; cat "$work/status"; }
token() { post /api/auth/login "{\"loginName\":\"$1\",\"password\":\"$2\"}" | field token; }

# register <登录名> <密码> <管理员令牌>：邀请并注册，输出注册响应体（含账号 id 与版本）
register() {
    local invitation
    invitation=$(post /api/invitations "{\"loginName\":\"$1\"}" "$3" | field secret)
    expect 200 "邀请 $1"
    post /api/auth/invitations/accept \
        "{\"invitationSecret\":\"$invitation\",\"loginName\":\"$1\",\"password\":\"$2\"}"
    expect 200 "注册 $1"
}

# initialize_admin <密码>：密码经标准输入写入容器内仅属服务用户的临时文件，不出现在命令参数中。
initialize_admin() {
    local output
    output=$(printf '%s' "$1" | compose run --rm -T --entrypoint sh iam -c \
        'umask 077; cat > /tmp/admin-password; exec java -jar /app/app.jar initialize-admin --login-name=admin --password-file=/tmp/admin-password' \
        2> "$work/init.log") || { cat "$work/init.log"; fail "管理员初始化"; }
    grep -q administrator_initialized <<< "$output" || fail "管理员初始化未确认"
}
