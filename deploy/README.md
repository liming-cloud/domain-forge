# 部署参考（Linux 主机＋Docker Compose）

单机基线：IAM、PostgreSQL、Redis、Nacos（只用作服务注册与发现）、Nginx。设计取舍见[总体架构](../docs/architecture/01-总体架构.md#身份权限部署与调用方案)，配置规则见[配置与运行](../docs/standards/08-配置与运行.md#部署与传输)。HTTPS 覆盖文件尚未交付，进度见[路线图](../docs/product/02-路线图.md#当前状态)。

| 服务 | 作用 | 对外端口 | 持久化 |
|---|---|---|---|
| nginx | 唯一公开入口，只转发 `/api/` 到 IAM | `NGINX_HTTP_BIND:NGINX_HTTP_PORT` | 无 |
| iam | 身份权限服务，启动后注册到 Nacos | 无 | 无（数据在 PostgreSQL） |
| postgres | IAM 业务数据 | 无 | 卷 `postgres-data` |
| redis | 会话、登录防护计数、缓存 | 无 | 不持久化，重启后所有用户需重新登录 |
| nacos | 服务注册与发现，开启鉴权 | 无 | 卷 `nacos-data`（账号与权限；实例信息可重建） |
| nacos-init | 一次性任务：设置管理员密码、创建服务专用账号 | 无 | 无 |

## 前提

- Linux 主机，Docker Engine 与 Compose v2；JDK 21（用于构建 IAM jar）；`python3`（`upgrade.sh` 读取 Compose 配置）。
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

## 备份与恢复

| 数据 | 处理 | 恢复后 |
|---|---|---|
| PostgreSQL（IAM 全部业务数据与审计） | `backup.sh` 在线逻辑备份 | 回到备份时刻，之后的变更丢失 |
| Redis（会话、登录防护计数、缓存） | 不备份 | 恢复时重启清空：所有用户需重新登录，恢复前的令牌全部失效 |
| Nacos（管理员与服务账号、权限；实例信息） | 不备份 | 账号与权限由 `nacos-init` 按环境文件重建，服务启动后重新注册；在控制台做过的其他修改需手工重做 |
| 环境文件 | 由运维与备份分开保管 | 恢复必须使用备份时的同一份（`IAM_FINGERPRINT_KEY` 不同则依赖指纹的数据无法匹配） |

```bash
# 备份：服务不停；生成 domainforge-<UTC 时间>.dump 与 .sha256，目录仅当前用户可访问
deploy/backup.sh /etc/domain-forge/df.env /var/backups/domain-forge

# 恢复：删除当前库全部数据，需显式 --yes；期间入口停止服务
deploy/restore.sh /etc/domain-forge/df.env /var/backups/domain-forge/domainforge-<时间>.dump --yes
```

恢复过程：校验摘要 → 停止 nginx、iam → 重建库并在单一事务内导入（失败则库保持为空，可修正后重试）→ 重启 Redis → 启动 iam、nginx 并等待健康。IAM 启动时 Flyway 只会向前迁移，因此可以把旧版本的备份恢复到新版本应用，反之不行。

备份含密码摘要与敏感输入指纹，按机密数据保存；异地副本、加密与保留周期由运维负责，本参考不提供定时任务。


## 升级与回退

镜像标签由环境文件中的 `IAM_IMAGE_TAG` 决定，建议使用应用版本号，旧版本镜像保留到确认不再回退。

```bash
# 1. 构建新版本 jar 与镜像（标签取自命令行，覆盖环境文件中的值）
platform/mvnw -f platform/pom.xml -B clean package -DskipTests -Ddocker.skip=true -pl services/iam -am
IAM_IMAGE_TAG=<新版本> docker compose -f deploy/compose.yml --env-file /etc/domain-forge/df.env build iam

# 2. 把环境文件中的 IAM_IMAGE_TAG 改为新版本，然后升级：先备份，再替换 iam 并等待健康
deploy/upgrade.sh /etc/domain-forge/df.env /var/backups/domain-forge
```

替换期间入口返回 502；IAM 启动时 Flyway 执行新版本的迁移。脚本失败时以非零退出并打印升级前备份的路径。

| 情况 | 回退方式 | 数据 |
|---|---|---|
| 新版本没有迁移，或迁移只新增（表、可空列、索引），旧版本仍能读写 | **应用回退**：`IAM_IMAGE_TAG` 改回旧版本，`docker compose ... up -d --no-build --wait iam nginx` | 保留升级后的全部数据；旧版本忽略它不认识的新迁移 |
| 迁移执行失败 | 同上，回到旧版本即可 | 不受影响：PostgreSQL 迁移在事务内执行，失败时整体回滚，不留历史记录 |
| 迁移改变或删除了旧版本依赖的结构，或新版本写入了旧版本无法处理的数据 | **数据回退**：`IAM_IMAGE_TAG` 改回旧版本，用升级前备份执行 `restore.sh` | 回到升级前，升级后的变更全部丢失，所有用户重新登录 |

应用回退与数据恢复分开判断：不承诺任意数据库变更都能自动逆转，也不提供 Flyway undo。发布新版本时须在说明中写明其迁移属于上表哪一类。

## 部署场景验证

`scripts/verify_deploy.sh`（CI 每次执行）在独立 Compose 项目中按接口行为核对，不以容器启动或备份文件存在代替：

| 场景 | 核对 |
|---|---|
| 首次部署、管理员初始化、普通重启 | 全部 healthy，仅 nginx 有主机端口；`/internal/`、管理端点经入口为 404；重启后账号仍可登录 |
| 备份、备份后继续变更、删除数据库卷、恢复 | 备份前的账号和密码可用，备份后注册的账号与改密不存在，恢复前的会话被拒绝，审计可查询 |
| 升级到带新增迁移的版本 | 自动生成升级前备份，迁移已执行，账号可登录 |
| 应用回退 | 旧版本在新结构上正常运行，新增的表保留 |
| 迁移失败的升级 | 脚本非零退出；失败迁移没有历史记录、没有残留表；回到上一版本后正常 |
| 数据回退 | 用升级前备份恢复到旧版本后，新版本的迁移与表都不存在，账号可登录 |

新版本由验证脚本在当前镜像上叠加验证专用迁移来模拟，不修改源码。

2026-10-26 本机实测（Apple Silicon，Docker 29.2，初始数据量）：首次启动 21—73 秒（含构建镜像时较长）；备份 1 秒内、约 33 KB；恢复（含 IAM 重启）15—20 秒；升级 9 秒；迁移失败的升级 7 秒内报告失败；数据回退 15 秒。耗时随数据量增长，生产数据量下需在部署环境实测。

## 本地调试

叠加 `compose.local.yml`，在回环地址发布 PostgreSQL、Redis、Nacos（含 gRPC 与控制台）端口，供 IDE 中运行的服务直连：

```bash
docker compose -f deploy/compose.yml -f deploy/compose.local.yml --env-file <环境文件> up -d --wait postgres redis nacos nacos-init
```

生产部署不要叠加该文件。

## 接入业务服务

模板生成的服务部署时：在 `compose.yml` 增加服务（构建方式同 IAM，启用 `<ENV>_DISCOVERY_ENABLED` 并使用 Nacos 服务账号），在 `nginx/default.conf` 为其公开前缀增加 `location`，不要转发 `/internal/`。
