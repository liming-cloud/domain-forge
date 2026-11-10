#!/bin/sh
# k6 容器的入口：在压测网络的网卡上加 10.231.4.1—10.231.7.254 共 1016 个源地址，每个虚拟用户固定一个；
# 每个地址模拟一个客户端（按地址的登录保护与网关限流分别计数）。需要 NET_ADMIN 与 root，参数原样交给 k6 run。
set -eu
nginx=$(nslookup nginx 127.0.0.11 | awk '/^Address: / { print $2 }' | tail -1)
for c in 4 5 6 7; do
    d=1
    while [ "$d" -le 254 ]; do
        ip addr add "10.231.$c.$d/16" dev eth0
        d=$((d + 1))
    done
done
# 从每个源地址向 Nginx 发一次 ARP 请求，让 Nginx 预先记下这些地址：否则 Nginx 对新地址的邻居解析常常失败，
# 建连要靠 SYN 重传，个别连接拖到 10 秒以上。预热后每个地址首次建连仍多约 1 秒（之后复用长连接），由预热阶段吸收。
for c in 4 5 6 7; do
    d=1
    while [ "$d" -le 254 ]; do
        arping -q -c 1 -w 1 -I eth0 -s "10.231.$c.$d" "$nginx" &
        d=$((d + 1))
    done
    wait
done
exec k6 run --local-ips=10.231.4.1-10.231.4.254,10.231.5.1-10.231.5.254,10.231.6.1-10.231.6.254,10.231.7.1-10.231.7.254 "$@"
