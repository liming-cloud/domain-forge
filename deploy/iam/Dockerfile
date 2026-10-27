# IAM 运行镜像：只打包 platform/mvnw 构建出的可执行 jar，不在镜像内编译源码。
# 构建上下文 dist 指向 platform/services/iam/target，其中必须恰好有一个可执行 jar。
FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c

RUN groupadd --system --gid 10001 iam \
    && useradd --system --uid 10001 --gid iam --no-create-home --shell /usr/sbin/nologin iam

WORKDIR /app
COPY --from=dist --chown=root:root --chmod=0444 iam-*.jar /app/iam.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/iam.jar"]
