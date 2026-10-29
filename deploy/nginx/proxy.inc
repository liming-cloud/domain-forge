# 转发到网关的设置。客户端地址：用 $remote_addr 覆盖 X-Forwarded-For，网关只信任 Nginx 的固定内网地址。
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header Connection "";
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Real-IP "";
proxy_set_header Forwarded "";
# 链路从网关开始：删除客户端带来的追踪头，不延续外部上下文（traceId 在响应头 X-Trace-Id 中返回）。
proxy_set_header traceparent "";
proxy_set_header tracestate "";
proxy_set_header baggage "";
proxy_connect_timeout 2s;
proxy_send_timeout 15s;
# 网关对下游的响应超时为 15 秒，这里放宽，保证由网关先超时并返回 504。
proxy_read_timeout 20s;
