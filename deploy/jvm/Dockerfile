# Java 服务运行镜像（IAM 与模板生成的业务服务共用）：只打包 platform/mvnw 构建出的可执行 jar，不在镜像内编译源码。
# 构建上下文 dist 指向服务模块的 target 目录，其中必须恰好有一个可执行 jar（*.jar.original 不计入）。
FROM eclipse-temurin:25-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c AS base

# 解包运行：AOT 缓存只对解包后的 jar 生效（嵌套 jar 不能缓存）。
FROM base AS extract
WORKDIR /build
COPY --from=dist *.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /app

FROM base
# curl 供容器内的健康检查使用（Temurin 25 镜像不再自带）。
# 文件存储与 AOT 缓存目录预先建好并归服务用户所有：挂载空的命名卷时，Docker 把目录属主复制到卷上，服务才能写入。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && install -d -o app -g app -m 0750 /var/lib/domain-forge/files /var/lib/domain-forge/aot

WORKDIR /app
COPY --from=extract --chown=root:root --chmod=0555 /app/lib /app/lib
COPY --from=extract --chown=root:root --chmod=0444 /app/app.jar /app/app.jar
COPY --chown=root:root --chmod=0555 start.sh /app/start.sh
# 缓存指纹：jar、依赖与 JDK 任一变化都换一个缓存文件，见 start.sh。
RUN cat /app/app.jar /app/lib/*.jar "$JAVA_HOME/release" | sha256sum | cut -c1-16 > /app/aot.key \
    && chmod 0444 /app/aot.key

USER 10001:10001
ENTRYPOINT ["/app/start.sh"]
