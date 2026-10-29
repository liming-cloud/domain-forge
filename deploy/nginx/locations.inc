# 入口的路由，HTTP（compose.yml）与 HTTPS（compose.https.yml）共用：/api/ 全部转发到网关，由网关按路由分发到各服务；
# /internal/、管理端点和其他路径一律不对外。
client_max_body_size 64k;

# 文件上传：路径以 /files 结尾的接口（如 POST /api/notes/{id}/files）放宽到 21m，与服务的 multipart 上限一致；
# 正则 location 优先于下面的 /api/ 前缀。超过时 Nginx 直接返回 413。
location ~ ^/api/.+/files$ {
    client_max_body_size 21m;
    set $gateway_upstream http://gateway:8000;
    proxy_pass $gateway_upstream;
    include /etc/nginx/domain-forge/proxy.inc;
}

location /api/ {
    set $gateway_upstream http://gateway:8000;
    proxy_pass $gateway_upstream;
    include /etc/nginx/domain-forge/proxy.inc;
}

location = /healthz {
    access_log off;
    default_type text/plain;
    return 200 "ok\n";
}

location / {
    return 404;
}
