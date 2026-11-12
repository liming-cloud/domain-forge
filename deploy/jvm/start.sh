#!/bin/sh
# Java 服务入口：用命名卷中与本镜像指纹相同的 AOT 缓存启动；没有时先做一次训练运行生成缓存（JEP 483/514）。
# 训练运行刷新完整上下文（连接数据库、Redis、配置中心并执行 Flyway）后在启动生命周期组件之前退出，
# 不开 HTTP 端口、不在 Nacos 注册、不执行 ApplicationRunner（定时任务 worker 在 Bean 初始化时启动，会短暂连上 PowerJob）。训练失败时不带缓存照常启动，只是慢一些。
# 卷中只保留最近两份缓存，回退到上一个镜像时仍能命中。
set -eu

# RocketMQ 客户端自带日志实现（不经 SLF4J），默认写服务用户不存在的家目录，失败时把 Logback 状态以文本打到标准输出；
# 指到容器内可写目录，标准输出只保留结构化 JSON 日志。消息收发结果由消息组件的日志与指标记录。
# 后两项放行第三方库对 JDK 受限接口的使用（netty 加载本地库；nacos、rocketmq、fastjson 内置的 guava 等调用 sun.misc.Unsafe），
# 否则 JDK 每次启动在标准错误打出非 JSON 警告。上游不再使用时去掉，见决定记录 0026。
set -- -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Drocketmq.log.root=/tmp/rocketmq \
    --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -jar /app/app.jar
dir=/var/lib/domain-forge/aot
cache="$dir/$(cat /app/aot.key).aot"

if [ ! -f "$cache" ]; then
    rm -f "$dir"/*.tmp
    # JVM 生成缓存时向标准输出、标准错误打印非 JSON 提示：训练输出先写入 training.log（排查时查看），
    # 结束后只把其中的 JSON 行（应用日志，含 Flyway 迁移记录）转到标准输出。
    if java -XX:AOTCacheOutput="$cache.tmp" -Dspring.context.exit=onRefresh "$@" > "$dir/training.log" 2>&1 \
        && [ -s "$cache.tmp" ]; then
        mv "$cache.tmp" "$cache"
        ls -t "$dir"/*.aot | tail -n +3 | xargs -r rm -f
    else
        rm -f "$cache.tmp"
    fi
    grep '^{' "$dir/training.log" || true
fi
if [ -f "$cache" ]; then
    set -- -XX:AOTCache="$cache" "$@"
fi
exec java "$@"
