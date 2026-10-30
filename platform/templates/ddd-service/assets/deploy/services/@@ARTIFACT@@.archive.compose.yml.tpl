# @@ARTIFACT@@ 便笺示例的归档库（附加数据源 archive）：与 deploy/services/@@ARTIFACT@@.compose.yml 一起列入 COMPOSE_FILE。
# 归档库是同一 PostgreSQL 实例中的独立数据库 @@SCHEMA@@_archive，属于本服务的数据库账号；backup.sh／restore.sh 一并备份恢复。
services:
  @@ARTIFACT@@-archive-db-init:
    # 一次性任务：建立归档库，可重复执行。账号不存在时先建（密码由 @@ARTIFACT@@-db-init 设置），与执行顺序无关。
    image: @@POSTGRES_IMAGE@@
    restart: "no"
    environment:
      PGHOST: postgres
      PGUSER: domainforge
      PGDATABASE: domainforge
      PGPASSWORD: ${POSTGRES_PASSWORD:?provide POSTGRES_PASSWORD}
    entrypoint: [sh, -c]
    command:
      - |
        set -e
        psql -v ON_ERROR_STOP=1 -q <<'SQL'
        SELECT 'CREATE ROLE @@SCHEMA@@' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '@@SCHEMA@@') \gexec
        SELECT 'CREATE DATABASE @@SCHEMA@@_archive OWNER @@SCHEMA@@'
        WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '@@SCHEMA@@_archive') \gexec
        SQL
        echo "@@SCHEMA@@_archive database ready"
    networks: [backend]
    depends_on:
      postgres:
        condition: service_healthy
  @@ARTIFACT@@:
    environment:
      @@ENV@@_ARCHIVE_DB_URL: jdbc:postgresql://postgres:5432/@@SCHEMA@@_archive
      @@ENV@@_ARCHIVE_DB_USER: @@SCHEMA@@
      @@ENV@@_ARCHIVE_DB_PASSWORD: ${@@ENV@@_DB_PASSWORD:?provide @@ENV@@_DB_PASSWORD}
    depends_on:
      @@ARTIFACT@@-archive-db-init:
        condition: service_completed_successfully
