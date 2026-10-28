# 入口的路由，HTTP（compose.yml）与 HTTPS（compose.https.yml）共用：/api/ 全部转发到网关，由网关按路由分发到各服务；
# /internal/、管理端点和其他路径一律不对外。
client_max_body_size 64k;

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
