# 部署验证脚本共用的函数，由 verify_deploy.sh、verify_closure.sh 引入，不单独执行。
# 引入前需设置：work（临时目录）、base（入口地址）、env_file（环境文件），并定义 compose 函数。

step() { echo "== $*"; }
fail() { echo "deploy_check=failed: $*" >&2; exit 1; }
secret() { python3 -I -c 'import secrets; print(secrets.token_hex(24))'; }
key() { python3 -I -c 'import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())'; }
free_port() { python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
field() { python3 -I -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }
size() { python3 -I -c 'import json, sys; print(len(json.load(sys.stdin)))'; }
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
POWERJOB_DB_PASSWORD=$(secret)
POWERJOB_ADMIN_PASSWORD=Pj-$(secret)
POWERJOB_APP_PASSWORD=App-$(secret)
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
# up_wait [compose up 参数...]：启动并等待健康（300 秒超时）。资源紧张时某个容器可能在启动阶段超时退出（如 IAM 连接 Redis），
# 由重启策略拉起后即可恢复，而 --wait 会因容器退出直接失败：失败时输出状态，再等待一次。
up_wait() {
    compose up -d --wait --wait-timeout 300 "$@" && return
    echo "首次等待未全部健康，容器状态如下，重试一次" >&2
    compose ps -a >&2
    compose up -d --wait --wait-timeout 300 "$@"
}

# login_is <登录名> <密码> <期望状态> <失败说明>：重建、回退或恢复后入口与 IAM 需要片刻才能接住请求，在 20 秒内重试到状态一致
login_is() {
    local attempt
    for attempt in $(seq 1 40); do
        [ "$(login "$1" "$2")" = "$3" ] && return
        sleep 0.5
    done
    fail "$4：期望 $3，实际 $(cat "$work/status") $(cat "$work/body")"
}
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

# check_json_logs <服务> [traceId]：服务的容器日志逐行为 ECS JSON 对象，带固定字段（时间、级别、日志器、服务名、消息），
# 给出 traceId 时还须在日志中找到它（网关访问日志的 traceId 在消息的 trace_id= 中）。输出 ok 或问题描述。
check_json_logs() {
    compose logs --no-color --no-log-prefix "$1" | python3 -I -c "$json_log_check" "$1" "${2:-}"
}
json_log_check='import json, sys
service, trace = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else ""
bad, missing, found, records = [], set(), False, 0
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        record = json.loads(line)
    except ValueError:
        record = None
    if not isinstance(record, dict):
        bad.append(line[:200])
        continue
    records += 1
    for path in ("@timestamp", "log.level", "log.logger", "service.name", "message"):
        value = record
        for key in path.split("."):
            value = value.get(key) if isinstance(value, dict) else None
        if value is None:
            missing.add(path)
    if record.get("service", {}).get("name") != service:
        missing.add("service.name=" + service)
    found = found or record.get("traceId") == trace or ("trace_id=" + trace) in str(record.get("message"))
problems = ([] if records else ["没有日志"]) + (["非 JSON 对象的行 %d 条，如 %s" % (len(bad), " ／ ".join(sorted(set(bad))[:3]))] if bad else []) \
    + (["缺少字段 " + ",".join(sorted(missing))] if missing else []) + ([] if found or not trace else ["未找到 traceId"])
print("; ".join(problems) or "ok")'

# dump_diagnostics：端到端失败时输出容器状态与日志尾部，CI 将其保存为产物（日志目录由 DIAGNOSTICS_DIR 指定）
dump_diagnostics() {
    local dir="${DIAGNOSTICS_DIR:-}"
    [ -n "$dir" ] || return 0
    mkdir -p "$dir"
    compose ps -a > "$dir/compose-ps.txt" 2>&1 || true
    compose logs --no-color --tail 300 > "$dir/compose-logs.txt" 2>&1 || true
}
