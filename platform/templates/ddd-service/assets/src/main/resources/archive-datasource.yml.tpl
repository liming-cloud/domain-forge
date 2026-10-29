# 便笺示例的归档库：附加数据源 archive（独立数据库），由 application.yml 以 optional 方式导入。
# 连接只来自环境变量，没有默认值；部署时由 deploy/services/@@ARTIFACT@@.archive.compose.yml 提供。
domainforge:
  datasources:
    archive:
      url: ${@@ENV@@_ARCHIVE_DB_URL}
      username: ${@@ENV@@_ARCHIVE_DB_USER}
      password: ${@@ENV@@_ARCHIVE_DB_PASSWORD}
