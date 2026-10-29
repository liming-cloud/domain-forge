# @@ARTIFACT@@ 服务配置。凭据与连接目标只来自环境变量，没有默认值；缺失时启动失败。
# 会话策略、Redis 连接资源和持久化默认值来自平台组件，这里只写差异。
spring:
  application:
    name: @@ARTIFACT@@
  config:
    # 带便笺示例时启用 Outbox、文件存储与归档库（附加数据源）；这些文件随示例生成，没有示例时不存在。
    import:
      - optional:classpath:messaging-outbox.yml
      - optional:classpath:file-storage.yml
      - optional:classpath:archive-datasource.yml
  main:
    banner-mode: off
  lifecycle:
    timeout-per-shutdown-phase: 10s
  cloud:
    nacos:
      discovery:
        # 本地可独立运行；部署时启用注册与发现，IAM 地址也从发现获得。
        enabled: ${@@ENV@@_DISCOVERY_ENABLED:false}
        server-addr: ${@@ENV@@_NACOS_SERVER_ADDR:127.0.0.1:8848}
        namespace: ${@@ENV@@_NACOS_NAMESPACE:public}
        group: ${@@ENV@@_NACOS_GROUP:DEFAULT_GROUP}
        username: ${@@ENV@@_NACOS_USERNAME:}
        password: ${@@ENV@@_NACOS_PASSWORD:}
    # Feign 超时（连接 1 秒、读取 2 秒）、不重试、不跟随重定向由 domain-forge-service-call 统一提供，
    # 个别客户端需要不同值时在 spring.cloud.openfeign.client.config.<contextId> 下覆盖。
  datasource:
    url: ${@@ENV@@_DB_URL}
    username: ${@@ENV@@_DB_USER}
    password: ${@@ENV@@_DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 0
      connection-timeout: 3000
      validation-timeout: 1000
      data-source-properties:
        connectTimeout: 3
        socketTimeout: 5
  flyway:
    enabled: true
    clean-disabled: true
    baseline-on-migrate: false
  transaction:
    default-timeout: 10s
  data:
    redis:
      # 与 IAM 共用会话 Redis：纯登录态由 Sa-Token 原生校验，权限与范围向 IAM 读取。
      host: ${@@ENV@@_REDIS_HOST}
      port: ${@@ENV@@_REDIS_PORT}
      password: ${@@ENV@@_REDIS_PASSWORD}
  servlet:
    multipart:
      # 上传内容由 Servlet 容器完整接收（超过 1MB 暂存到临时目录）后再进入用例；超过上限返回 413。
      # 与 domainforge.files.max-size 及入口 Nginx 对 /files 路径的上限保持一致。
      max-file-size: 20MB
      max-request-size: 21MB
      file-size-threshold: 1MB
  jackson:
    deserialization:
      fail-on-unknown-properties: true

# 本地直连 IAM 时设置 @@ENV@@_IAM_URL（如 http://127.0.0.1:8080）；为空时经 Nacos 发现 iam。
# 消息组件连接 RocketMQ Proxy 的地址；只有发布或消费事件时才连接，部署时为 rocketmq-broker:8081。
# 定时任务（PowerJob）：实现 ScheduledTask 的 Bean 由调度组件登记；部署时启用，本地与测试默认不启用。
# 文件存储（启用后）：默认本地目录，部署时为容器内挂载的命名卷；改用 S3 兼容存储时设置 @@ENV@@_FILES_STORAGE=s3
# 与 @@ENV@@_FILES_S3_* （桶由运维事先创建，密钥只来自环境变量），见 docs/architecture/11-文件存储.md。
domainforge:
  files:
    storage: ${@@ENV@@_FILES_STORAGE:local}
    max-size: 20MB
    local:
      root: ${@@ENV@@_FILES_ROOT:}
    s3:
      endpoint: ${@@ENV@@_FILES_S3_ENDPOINT:}
      region: ${@@ENV@@_FILES_S3_REGION:us-east-1}
      bucket: ${@@ENV@@_FILES_S3_BUCKET:}
      access-key: ${@@ENV@@_FILES_S3_ACCESS_KEY:}
      secret-key: ${@@ENV@@_FILES_S3_SECRET_KEY:}
  messaging:
    endpoints: ${@@ENV@@_MESSAGING_ENDPOINTS:}
  scheduling:
    enabled: ${@@ENV@@_SCHEDULING_ENABLED:false}
    server-address: ${@@ENV@@_SCHEDULING_SERVER_ADDRESS:}
    app-password: ${@@ENV@@_SCHEDULING_APP_PASSWORD:}

@@ARTIFACT@@:
  iam:
    url: ${@@ENV@@_IAM_URL:}

mybatis-plus:
  configuration:
    default-statement-timeout: 5
    map-underscore-to-camel-case: true
    cache-enabled: false
    local-cache-scope: STATEMENT
  global-config:
    banner: false

server:
  port: ${@@ENV@@_PORT:8090}
  shutdown: graceful
  forward-headers-strategy: none
  error:
    include-message: never
    include-stacktrace: never

management:
  server:
    address: 127.0.0.1
    port: ${@@ENV@@_MANAGEMENT_PORT:9092}
  endpoints:
    web:
      exposure:
        include: health,metrics
  endpoint:
    health:
      show-details: never
      probes:
        enabled: true
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db,redis
      status:
        order: down,out-of-service,degraded,unknown,up
  metrics:
    tags:
      application: @@ARTIFACT@@

---
# 配置中心：部署时以 SPRING_PROFILES_ACTIVE=config-center 启用。Nacos 不可达或 dataId 未发布时启动失败。
# 需要不重启生效的配置组用 domain-forge-config 的 DynamicSettingsBinder 声明，见 docs/architecture/05-配置中心.md。
spring:
  config:
    activate:
      on-profile: config-center
    import: "nacos:@@ARTIFACT@@.yaml?refreshEnabled=true"
  cloud:
    nacos:
      config:
        server-addr: ${@@ENV@@_NACOS_SERVER_ADDR:127.0.0.1:8848}
        namespace: ${@@ENV@@_NACOS_NAMESPACE:public}
        group: ${@@ENV@@_NACOS_GROUP:DEFAULT_GROUP}
        username: ${@@ENV@@_NACOS_USERNAME:}
        password: ${@@ENV@@_NACOS_PASSWORD:}
domainforge:
  config:
    expected-source: nacos
