# @@ARTIFACT@@ 服务配置。凭据与连接目标只来自环境变量，没有默认值；缺失时启动失败。
# 会话策略、Redis 连接资源和持久化默认值来自平台组件，这里只写差异。
spring:
  application:
    name: @@ARTIFACT@@
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
    openfeign:
      client:
        config:
          iamAccess:
            connect-timeout: 1000
            read-timeout: 2000
            follow-redirects: false
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
  jackson:
    deserialization:
      fail-on-unknown-properties: true

# 本地直连 IAM 时设置 @@ENV@@_IAM_URL（如 http://127.0.0.1:8080）；为空时经 Nacos 发现 iam。
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
