# @@ARTIFACT@@ 的公开前缀：比 IAM 的 /api/ 更具体，优先匹配。新增公开接口时在此补充，不要转发 /internal/。
location = /api/notes {
    set $@@SCHEMA@@_upstream http://@@ARTIFACT@@:8090;
    proxy_pass $@@SCHEMA@@_upstream;
    include /etc/nginx/domain-forge/proxy.inc;
}

location /api/notes/ {
    set $@@SCHEMA@@_upstream http://@@ARTIFACT@@:8090;
    proxy_pass $@@SCHEMA@@_upstream;
    include /etc/nginx/domain-forge/proxy.inc;
}

location = /api/me/notes {
    set $@@SCHEMA@@_upstream http://@@ARTIFACT@@:8090;
    proxy_pass $@@SCHEMA@@_upstream;
    include /etc/nginx/domain-forge/proxy.inc;
}
