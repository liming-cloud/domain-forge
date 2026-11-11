#!/bin/sh
# k6 容器的入口：在压测网络的网卡上加 10.231.4.1—10.231.5.226 共 480 个源地址，每个虚拟用户固定一个；
# 每个地址模拟一个客户端（按地址的登录保护与网关限流分别计数）。需要 NET_ADMIN 与 root，参数原样交给 k6 run。
# 地址数受 Nginx 所在网络命名空间的邻居表限制：表项超过约 512 个（内核 gc_thresh2）时被频繁回收，
# 已建立的连接上的响应会丢失，所以不超过 480 个。
set -eu
nginx=$(nslookup nginx 127.0.0.11 | awk '/^Address: / { print $2 }' | tail -1)
addresses() {
    d=1
    while [ "$d" -le 254 ]; do echo "10.231.4.$d"; d=$((d + 1)); done
    d=1
    while [ "$d" -le 226 ]; do echo "10.231.5.$d"; d=$((d + 1)); done
}
for a in $(addresses); do ip addr add "$a/16" dev eth0; done
# 从每个源地址向 Nginx 发一次 ARP 请求，让 Nginx 预先记下这些地址，首次建连不必等邻居解析（每批 64 个并发）。
n=0
for a in $(addresses); do
    arping -q -c 1 -w 1 -I eth0 -s "$a" "$nginx" &
    n=$((n + 1))
    [ $((n % 64)) -eq 0 ] && wait
done
wait
exec k6 run --local-ips=10.231.4.1-10.231.4.254,10.231.5.1-10.231.5.226 "$@"
