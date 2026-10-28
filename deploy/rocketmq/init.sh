#!/bin/sh
# 一次性初始化 RocketMQ：等 broker 注册到 namesrv，按 topics.conf 创建主题与消费组，再等每个主题的路由可见后退出。
# 可重复执行；主题类型（NORMAL／FIFO）创建后不应修改，修改需要新建主题。
set -eu

admin() { ./mqadmin "$@" -n "$NAMESRV_ADDR" 2>/dev/null; }

for attempt in $(seq 1 60); do
    admin clusterList | grep -q broker-a && break
    [ "$attempt" = "60" ] && { echo "rocketmq broker not registered" >&2; exit 1; }
    sleep 2
done

topics=""
while read -r kind name mode; do
    case "$kind" in
        topic)
            case "$mode" in
                NORMAL) admin updateTopic -c DefaultCluster -t "$name" -a +message.type=NORMAL | grep -q success ;;
                FIFO) admin updateTopic -c DefaultCluster -t "$name" -o true -a +message.type=FIFO | grep -q success ;;
                *) echo "unknown topic type for $name: $mode" >&2; exit 1 ;;
            esac
            topics="$topics $name"
            echo "rocketmq topic ready: $name ($mode)" ;;
        group)
            case "$mode" in
                CONCURRENT) admin updateSubGroup -c DefaultCluster -g "$name" -r 4 | grep -q groupName ;;
                ORDERLY) admin updateSubGroup -c DefaultCluster -g "$name" -o true -r 4 | grep -q groupName ;;
                *) echo "unknown group mode for $name: $mode" >&2; exit 1 ;;
            esac
            echo "rocketmq group ready: $name ($mode)" ;;
        ''|'#'*) ;;
        *) echo "unknown line in topics.conf: $kind" >&2; exit 1 ;;
    esac
done < /init/topics.conf

# broker 定期向 namesrv 上报路由；路由可见前启动的客户端会取不到主题。
for topic in $topics; do
    for attempt in $(seq 1 60); do
        admin topicRoute -t "$topic" | grep -q brokerDatas && break
        [ "$attempt" = "60" ] && { echo "rocketmq route not visible: $topic" >&2; exit 1; }
        sleep 1
    done
done
echo "rocketmq init done"
