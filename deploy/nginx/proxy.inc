# 各后端 location 共用的转发设置。客户端地址：用 $remote_addr 覆盖 X-Forwarded-For，
# 后端只信任 Nginx 的固定内网地址。
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header Connection "";
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Real-IP "";
proxy_set_header Forwarded "";
# 请求标识由入口生成，覆盖客户端传入的值；后端写入日志并在响应头返回。
proxy_set_header X-Request-Id $request_id;
proxy_connect_timeout 2s;
proxy_send_timeout 15s;
proxy_read_timeout 15s;
