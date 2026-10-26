# 部署参考（Linux 主机＋Docker Compose）

单机基线：IAM、PostgreSQL、Redis、Nacos（只用作服务注册与发现）、Nginx。设计取舍见[总体架构](../docs/architecture/01-总体架构.md#身份权限部署与调用方案)，配置规则见[配置与运行](../docs/standards/08-配置与运行.md#部署与传输)。备份恢复、升级回退与 HTTPS 覆盖文件尚未交付，进度见[路线图](../docs/product/02-路线图.md#当前状态)。

| 服务 | 作用 | 对外端口 | 持久化 |
|---|---|---|---|
| nginx | 唯一公开入口，只转发 `/api/` 到 IAM | `NGINX_HTTP_BIND:NGINX_HTTP_PORT` | 无 |
| iam | 身份权限服务，启动后注册到 Nacos | 无 | 无（数据在 PostgreSQL） |
| postgres | IAM 业务数据 | 无 | 卷 `postgres-data` |
| redis | 会话、登录防护计数、缓存 | 无 | 不持久化，重启后所有用户需重新登录 |
| nacos | 服务注册与发现，开启鉴权 | 无 | 卷 `nacos-data`（账号与权限；实例信息可重建） |
| nacos-init | 一次性任务：设置管理员密码、创建服务专用账号 | 无 | 无 |

## 前提

- Linux 主机，Docker Engine 与 Compose v2；JDK 21（用于构建 IAM jar）。
- 网段 `172.31.250.0/24` 未被主机其他网络占用。冲突时同步修改 `compose.yml` 中的网段、Nginx 固定地址和 IAM 的 `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`。

## 首次部署

```bash
# 1. 构建 IAM 可执行 jar（必须 clean，target 中残留的旧资源或旧 jar 会被打进镜像或使镜像构建失败）
platform/mvnw -f platform/pom.xml -B clean package -DskipTests -Ddocker.skip=true -pl services/iam -am

# 2. 准备环境文件：复制到仓库外并限制权限，按下方方法填写
cp deploy/env.example /etc/domain-forge/df.env && chmod 600 /etc/domain-forge/df.env

# 3. 构建镜像并启动，等待全部健康
docker compose -f deploy/compose.yml --env-file /etc/domain-forge/df.env up -d --build --wait

# 4. 初始化 IAM 管理员（只能执行一次；交互输入密码，不回显、不进日志）
docker compose -f deploy/compose.yml --env-file /etc/domain-forge/df.env run --rm -it iam initialize-admin --login-name=admin
```

环境文件中的随机值可以这样生成：

| 变量 | 生成 |
|---|---|
| `POSTGRES_PASSWORD`、`REDIS_PASSWORD`、`NACOS_ADMIN_PASSWORD`、`NACOS_SERVICE_PASSWORD`、`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE` | `openssl rand -hex 24` |
| `NACOS_AUTH_TOKEN`、`IAM_FINGERPRINT_KEY` | `openssl rand -base64 32` |

生产环境把 `NGINX_HTTP_BIND` 改为 `0.0.0.0` 或指定网卡地址；默认只绑定回环地址。

## 验证

```bash
docker compose -f deploy/compose.yml --env-file /etc/domain-forge/df.env ps   # 全部 healthy，nacos-init 为 Exited (0)，只有 nginx 有主机端口
curl -s http://127.0.0.1:8080/healthz                                          # ok
curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' \
     -d '{"loginName":"admin","password":"<初始化时输入的密码>"}'                # 返回 token
```

`/internal/`、管理端点和其他路径经 Nginx 一律返回 404。

## 安全要点

- **客户端地址**：Nginx 用 `$remote_addr` 覆盖 `X-Forwarded-For`；IAM 只信任 Nginx 固定地址给出的该头，登录防护按真实客户端地址计数，客户端伪造的头无效。前面再加一层负载均衡时，需要把它的地址加入 Nginx 的 `set_real_ip_from`，否则所有请求都会被算成同一个地址。
- **Nacos 鉴权**：管理员密码只在首次启动时由 `nacos-init` 设置，在此之前 Nacos 的初始化接口对网络内可见，因此 8848／9848／8080 不能发布到公网。服务只用 `NACOS_SERVICE_USERNAME` 账号，权限仅为指定命名空间的服务注册与发现读写。
- **改密**：`nacos-init` 不会修改已存在账号的密码。修改 Nacos 管理员或服务账号密码时，先在 Nacos 控制台修改，再同步环境文件。
- **管理端口**：IAM 管理端口只绑定容器内回环地址，健康检查在容器内执行。

## 停止与数据

```bash
docker compose -f deploy/compose.yml --env-file /etc/domain-forge/df.env down   # 保留卷
```

不要使用 `down -v`，它会删除 PostgreSQL 与 Nacos 的卷。数据库迁移不执行 clean／repair。

## 本地调试

叠加 `compose.local.yml`，在回环地址发布 PostgreSQL、Redis、Nacos（含 gRPC 与控制台）端口，供 IDE 中运行的服务直连：

```bash
docker compose -f deploy/compose.yml -f deploy/compose.local.yml --env-file <环境文件> up -d --wait postgres redis nacos nacos-init
```

生产部署不要叠加该文件。

## 接入业务服务

模板生成的服务部署时：在 `compose.yml` 增加服务（构建方式同 IAM，启用 `<ENV>_DISCOVERY_ENABLED` 并使用 Nacos 服务账号），在 `nginx/default.conf` 为其公开前缀增加 `location`，不要转发 `/internal/`。
