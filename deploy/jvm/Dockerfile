# Java 服务运行镜像（IAM 与模板生成的业务服务共用）：只打包 platform/mvnw 构建出的可执行 jar，不在镜像内编译源码。
# 构建上下文 dist 指向服务模块的 target 目录，其中必须恰好有一个可执行 jar（*.jar.original 不计入）。
FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c

# 文件存储目录预先建好并归服务用户所有：挂载空的命名卷时，Docker 把目录属主复制到卷上，服务才能写入。
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && install -d -o app -g app -m 0750 /var/lib/domain-forge/files

WORKDIR /app
COPY --from=dist --chown=root:root --chmod=0444 *.jar /app/app.jar

USER 10001:10001
# RocketMQ 客户端自带日志实现（不经 SLF4J），默认写服务用户不存在的家目录，失败时把 Logback 状态以文本打到标准输出；
# 指到容器内可写目录，标准输出只保留结构化 JSON 日志。消息收发结果由消息组件的日志与指标记录。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Drocketmq.log.root=/tmp/rocketmq", \
        "-jar", "/app/app.jar"]
