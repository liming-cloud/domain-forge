# @@ARTIFACT@@ 的部署覆盖文件，与 deploy/compose.yml 一起使用（见 deploy/README.md 的“接入业务服务”）。
# 环境文件需增加 @@ENV@@_DB_PASSWORD（本服务数据库账号的密码）。
services:
  @@ARTIFACT@@-db-init:
    # 一次性任务：建立本服务专用的数据库账号与同名 schema，可重复执行，每次把密码同步为环境文件中的值。
    # 账号只拥有自己的 schema，读不到 IAM 和其他服务的表。镜像与生成时 deploy/compose.yml 的 postgres 一致。
    image: @@POSTGRES_IMAGE@@
    restart: "no"
    environment:
      PGHOST: postgres
      PGUSER: domainforge
      PGDATABASE: domainforge
      PGPASSWORD: ${POSTGRES_PASSWORD:?provide POSTGRES_PASSWORD}
      SERVICE_DB_PASSWORD: ${@@ENV@@_DB_PASSWORD:?provide @@ENV@@_DB_PASSWORD}
    entrypoint: [sh, -c]
    command:
      - |
        psql -v ON_ERROR_STOP=1 -q <<'SQL'
        \getenv service_password SERVICE_DB_PASSWORD
        SELECT 'CREATE ROLE @@SCHEMA@@' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '@@SCHEMA@@') \gexec
        ALTER ROLE @@SCHEMA@@ WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'service_password';
        CREATE SCHEMA IF NOT EXISTS @@SCHEMA@@ AUTHORIZATION @@SCHEMA@@;
        SQL
        echo "@@SCHEMA@@ database account ready"
    networks: [backend]
    depends_on:
      postgres:
        condition: service_healthy
  @@ARTIFACT@@:
    image: domain-forge/@@ARTIFACT@@:${@@ENV@@_IMAGE_TAG:-local}
    build:
      context: ./jvm
      additional_contexts:
        dist: ../platform/services/@@ARTIFACT@@/target
    restart: unless-stopped
    logging:
      driver: json-file
      options:
        max-size: 10m
        max-file: "5"
    environment:
      @@ENV@@_DB_URL: jdbc:postgresql://postgres:5432/domainforge?currentSchema=@@SCHEMA@@
      @@ENV@@_DB_USER: @@SCHEMA@@
      @@ENV@@_DB_PASSWORD: ${@@ENV@@_DB_PASSWORD:?provide @@ENV@@_DB_PASSWORD}
      SPRING_FLYWAY_SCHEMAS: @@SCHEMA@@
      @@ENV@@_REDIS_HOST: redis
      @@ENV@@_REDIS_PORT: "6379"
      @@ENV@@_REDIS_PASSWORD: ${REDIS_PASSWORD:?provide REDIS_PASSWORD}
      # IAM 地址留空：经 Nacos 发现名为 iam 的服务。
      @@ENV@@_DISCOVERY_ENABLED: "true"
      @@ENV@@_NACOS_SERVER_ADDR: nacos:8848
      @@ENV@@_NACOS_USERNAME: ${NACOS_SERVICE_USERNAME:-domainforge}
      @@ENV@@_NACOS_PASSWORD: ${NACOS_SERVICE_PASSWORD:?provide NACOS_SERVICE_PASSWORD}
    networks: [backend]
    depends_on:
      @@ARTIFACT@@-db-init:
        condition: service_completed_successfully
      redis:
        condition: service_healthy
      nacos-init:
        condition: service_completed_successfully
    healthcheck:
      # 管理端口只绑定容器内回环地址，不对网络开放。
      test: [CMD-SHELL, 'curl -sf http://127.0.0.1:9092/actuator/health/readiness > /dev/null']
      interval: 3s
      timeout: 3s
      start_period: 30s
      retries: 40
