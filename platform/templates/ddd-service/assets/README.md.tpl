# @@ARTIFACT@@

由 `platform/templates/ddd-service` 生成的业务服务，生成后归本服务维护。研发前先读仓库根目录的 `AGENTS.md` 与 [模板使用说明](../../templates/ddd-service/01-使用说明.md)。

## 运行

| 环境变量 | 含义 |
|---|---|
| `@@ENV@@_DB_URL`、`@@ENV@@_DB_USER`、`@@ENV@@_DB_PASSWORD` | 本服务自己的 PostgreSQL 库，启动时执行 Flyway 迁移 |
| `@@ENV@@_REDIS_HOST`、`@@ENV@@_REDIS_PORT`、`@@ENV@@_REDIS_PASSWORD` | 与 IAM 共用的会话 Redis |
| `@@ENV@@_IAM_URL` | 直连 IAM 的地址；为空时经 Nacos 发现名为 `iam` 的服务 |
| `@@ENV@@_DISCOVERY_ENABLED`、`@@ENV@@_NACOS_SERVER_ADDR` | 是否注册到 Nacos 及其地址 |
| `@@ENV@@_PORT`、`@@ENV@@_MANAGEMENT_PORT` | 业务端口（默认 8090）与回环管理端口（默认 9092） |

权限命名空间为 `@@NAMESPACE@@`。服务的权限码须先由系统管理员安装到 IAM 权限目录（`PUT /api/permission-namespaces/@@NAMESPACE@@`），再授予角色。

## 验证

```bash
platform/mvnw -f platform/pom.xml verify -pl services/@@ARTIFACT@@ -am
```
