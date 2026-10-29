# 部署参考（Linux 主机＋Docker Compose）

单机基线：Nginx、API 网关、IAM、PostgreSQL、Redis、Nacos（服务注册与发现、配置中心），以及模板生成的业务服务（见[接入业务服务](#接入业务服务)）。设计取舍见[总体架构](../docs/architecture/01-总体架构.md#身份权限部署与调用方案)，配置规则见[配置与运行](../docs/standards/08-配置与运行.md#部署与传输)。启用 HTTPS 见 [HTTPS](#https)。

| 服务 | 作用 | 对外端口 | 持久化 |
|---|---|---|---|
| nginx | 唯一公开入口：`/api/` 全部转发到网关，删除客户端带来的追踪头 | `NGINX_HTTP_BIND:NGINX_HTTP_PORT` | 无 |
| gateway | API 网关：按 Nacos 中 `gateway.yaml` 的路由分发到各服务，登录前置校验、限流，写入 Same-Token（见[API 网关](../docs/architecture/06-API网关.md)） | 无 | 无（路由在 Nacos） |
| iam | 身份权限服务，启动后注册到 Nacos | 无 | 无（数据在 PostgreSQL） |
| postgres | 库 `domainforge`：IAM 在 `public`，每个业务服务一个同名 schema | 无 | 卷 `postgres-data` |
| redis | 会话、登录防护计数、缓存 | 无 | 不持久化，重启后所有用户需重新登录 |
| nacos | 服务注册与发现、配置中心，开启鉴权 | 无 | 卷 `nacos-data`（账号与权限、配置；实例信息可重建） |
| nacos-init | 一次性任务：设置管理员密码、创建服务专用账号、发布配置种子 | 无 | 无 |
| rocketmq-namesrv、rocketmq-broker | 集成事件的传输：broker 内置 proxy，服务经 `rocketmq-broker:8081` 连接（见[消息与 Outbox](../docs/architecture/08-消息与Outbox.md#部署)） | 无 | 卷 `rocketmq-store`（不进入备份，Outbox 在 PostgreSQL 中） |
| powerjob-server | 定时任务调度与控制台（见[定时任务](#定时任务)） | 无（控制台经 `compose.console.yml` 在回环地址发布） | 无（表在 PostgreSQL 的 `powerjob` schema） |
| powerjob-db-init、powerjob-init | 一次性任务：PowerJob 数据库账号与 schema；命名空间与各服务的应用注册（`powerjob/apps.conf`） | 无 | 无 |
| rocketmq-store-init、rocketmq-init | 一次性任务：修正存储卷属主；按 `rocketmq/topics.conf` 创建主题与消费组 | 无 | 无 |
| `<服务名>`、`<服务名>-db-init` | 业务服务及其数据库账号初始化（覆盖文件 `services/<服务名>.compose.yml`） | 无 | 无（数据在 PostgreSQL） |

## 前提

- Linux 主机，Docker Engine 与 Compose v2；JDK 21（用于构建 IAM 与网关 jar）；`python3`（`upgrade.sh` 读取 Compose 配置）；启用 HTTPS 时需要 `openssl`（`tls-reload.sh` 检查证书）。
- 网段 `172.31.250.0/24` 未被主机其他网络占用。冲突时同步修改 `compose.yml` 中的网段、Nginx 与网关的固定地址、网关的 `GATEWAY_TRUSTED_PROXIES`（Nginx 地址）和 IAM 的 `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`（网关地址）。

## 首次部署

```bash
# 1. 构建 IAM 与网关的可执行 jar（必须 clean，target 中残留的旧资源或旧 jar 会被打进镜像或使镜像构建失败）
platform/mvnw -f platform/pom.xml -B clean package -DskipTests -Ddocker.skip=true -pl services/iam,services/gateway -am

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

`/internal/`、管理端点和其他路径经入口一律返回 404；可能被后端规范化成其他路径的写法（如 `%2e%2e`、`..;`）在网关返回 400。

## 安全要点

- **客户端地址**：Nginx 用 `$remote_addr` 覆盖 `X-Forwarded-For`；网关只信任 Nginx 固定地址给出的该头，转发时再以确定的客户端地址覆盖；IAM 只信任网关固定地址给出的该头。登录防护与网关限流都按真实客户端地址计数，客户端伪造的头无效。前面再加一层负载均衡时，需要把它的地址加入 Nginx 的 `set_real_ip_from`，否则所有请求都会被算成同一个地址。
- **直连后端**：后端只在内部网络可达，并校验网关与服务间调用携带的 Same-Token（见[API 网关](../docs/architecture/06-API网关.md#same-token)），网段内其他容器绕过网关直连返回 401。部署环境（`config-center` profile）不能关闭该校验，关闭即启动失败。
- **限流**：网关按客户端地址限流（默认每秒 20 个、突发 40 个，超出 429），阈值在 Nacos 的 `gateway.yaml` 中调整、立即生效。前面再加负载均衡或客户端经同一 NAT 出口时，需要相应调高。
- **链路标识**：Nginx 删除客户端带来的 `traceparent`、`tracestate`、`baggage`，链路从网关开始；响应头 `X-Trace-Id` 即 traceId，网关访问日志（`gateway_access trace_id=…`）与各服务日志都带上它，排查时按它关联。
- **链路导出**：默认不导出。业务项目提供 OTLP 采集端后，用自己的 Compose 覆盖文件为各服务设置环境变量 `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`（如 `http://collector:4318/v1/traces`，写法可参照 `scripts/compose.tracing-verify.yml`）；新链路采样率在 Nacos 配置组 `domainforge.tracing` 的 `sampling-probability` 调整（默认 0.1），见[链路追踪](../docs/architecture/10-链路追踪.md)。
- **Nacos 鉴权**：管理员密码只在首次启动时由 `nacos-init` 设置，在此之前 Nacos 的初始化接口对网络内可见，因此 8848／9848／8080 不能发布到公网。服务只用 `NACOS_SERVICE_USERNAME` 账号，权限仅为指定命名空间的服务注册与发现读写。
- **改密**：`nacos-init` 不会修改已存在账号的密码。修改 Nacos 管理员或服务账号密码时，先在 Nacos 控制台修改，再同步环境文件。
- **管理端口**：IAM 管理端口只绑定容器内回环地址，健康检查在容器内执行。
- **请求体上限**：Nginx 对普通接口限制请求体为 64k；只有路径以 `/files` 结尾的上传接口放宽到 21m（`nginx/locations.inc`），与服务的 multipart 上限 20MB 一致。

## 停止与数据

```bash
docker compose -f deploy/compose.yml --env-file /etc/domain-forge/df.env down   # 保留卷
```

不要使用 `down -v`，它会删除 PostgreSQL、Nacos 与业务服务文件存储的卷。数据库迁移不执行 clean／repair。

## 备份与恢复

| 数据 | 处理 | 恢复后 |
|---|---|---|
| PostgreSQL（IAM 与各业务服务的 schema，含对象属主） | `backup.sh` 在线逻辑备份 | 回到备份时刻，之后的变更丢失；业务服务数据库账号由其 `db-init` 按环境文件重建 |
| 其他业务数据库（服务的附加数据源，如归档库 `<schema>_archive`） | `backup.sh` 逐个导出为同名 `.db.<库名>.dump` | `restore.sh` 按备份重建（保留属主，账号与库由服务的 db-init 先建好）；备份中没有的库保持不变 |
| 业务服务的文件（命名卷 `<服务名>-files`） | `backup.sh` 在导出数据库之后打包为同名 `.files.<服务名>.tar` | `restore.sh` 用备份替换卷中的内容；备份时刻之后上传的文件消失，多出的对象由清理任务删除。备份中没有该文件（功能上线前的备份）时保留现有文件并提示 |
| Redis（会话、登录防护计数、缓存） | 不备份 | 恢复时重启清空：所有用户需重新登录，恢复前的令牌全部失效 |
| Nacos 配置（含控制台修改） | `backup.sh` 经管理接口导出为同名 `.nacos.json` | `restore.sh` 发布备份中的每项配置；Nacos 中存在而备份中没有的配置保持不变 |
| Nacos 账号、权限与实例 | 不备份 | 账号与权限由 `nacos-init` 按环境文件重建，服务启动后重新注册；在控制台做过的账号修改需手工重做 |
| 环境文件 | 由运维与备份分开保管 | 恢复必须使用备份时的同一份（`IAM_FINGERPRINT_KEY` 不同则依赖指纹的数据无法匹配） |

```bash
# 备份：服务不停；生成 domainforge-<UTC 时间>.dump 与 .sha256，目录仅当前用户可访问
deploy/backup.sh /etc/domain-forge/df.env /var/backups/domain-forge

# 恢复：删除当前库全部数据，需显式 --yes；期间入口停止服务
deploy/restore.sh /etc/domain-forge/df.env /var/backups/domain-forge/domainforge-<时间>.dump --yes
```

恢复过程：校验摘要 → 停止全部应用服务（nginx、网关、iam、业务服务）→ 重建库并在单一事务内导入（失败则库保持为空，可修正后重试）→ 重启 Redis → 启动全部服务并等待健康。IAM 启动时 Flyway 只会向前迁移，因此可以把旧版本的备份恢复到新版本应用，反之不行。

备份含密码摘要与敏感输入指纹，按机密数据保存；异地副本、加密与保留周期由运维负责，本参考不提供定时任务。


## 定时任务

服务实现 `ScheduledTask` 的任务由 PowerJob 调度（设计与执行语义见[定时任务](../docs/architecture/09-定时任务.md)）：同一任务同一时刻只在一个实例上执行，停机期间错过的触发不补跑，失败不自动重试。

- **控制台**：`COMPOSE_FILE=deploy/compose.yml:deploy/compose.console.yml docker compose --env-file <环境文件> up -d powerjob-server` 后，经 SSH 隧道访问 `http://127.0.0.1:${POWERJOB_CONSOLE_PORT:-7700}`，以 `ADMIN`／`POWERJOB_ADMIN_PASSWORD` 登录。在控制台修改周期、暂停或恢复、手动触发并查看执行记录；修改立即生效，重新部署不会覆盖（服务启动时只登记不存在的任务）。
- **应用**：每个服务一个应用，名称与服务名相同，在 `powerjob/apps.conf` 登记，`powerjob-init` 在每次部署时注册尚不存在的应用；所有服务共用 `POWERJOB_APP_PASSWORD`。
- **备份**：PowerJob 的表在 `domainforge` 库的 `powerjob` schema 中，任务定义（含控制台修改）与执行记录随 `backup.sh` 备份、`restore.sh` 恢复。执行记录保留 30 天。
- **改密**：`POWERJOB_ADMIN_PASSWORD` 只在首次启动时生效，之后在控制台修改并同步环境文件；应用密码同理。

## 消息

服务间的集成事件经 RocketMQ 传输，发布方以同事务 Outbox 保证“业务提交即事件不丢”（设计见[消息与 Outbox](../docs/architecture/08-消息与Outbox.md)）：

- 主题与消费组在 `rocketmq/topics.conf` 登记，`rocketmq-init` 每次部署按它创建（已存在的更新）；关闭了自动创建，拼错名字会报错而不是静默新建。主题类型创建后不改。
- RocketMQ 停机时业务照常提交，事件留在各服务的 `domainforge_outbox` 表中，恢复后继续发送；积压看指标 `domainforge.messaging.outbox.pending`。
- 消费一直失败的消息共投递 5 次后进入死信队列 `%DLQ%<消费组>`，指标 `domainforge.messaging.dead-letter` 增加；排查后用 RocketMQ 自带工具处理，本参考不提供重放脚本。
- 消息存储卷不进入备份；丢失它只会丢失“已发送、尚未消费”的消息。从备份恢复后，Outbox 与消费方的去重表都回到备份时刻。

## 文件存储

业务服务的文件默认保存在服务自己的命名卷 `<服务名>-files`（容器内 `/var/lib/domain-forge/files`），设计与合同见[文件存储](../docs/architecture/11-文件存储.md)：

- **授权**：上传与下载都经业务服务自己的接口，先按权限与数据范围判断；文件卷和存储不对外暴露。
- **删除与清理**：删除只做标记，24 小时后由定时任务 `file-storage-cleanup`（PowerJob 控制台中各服务的应用下）删除对象；该任务同时删除没有记录的孤儿对象。恢复较早的备份后，可在控制台手动触发并填写参数 `days=N`（检查最近 N 天的目录）。
- **备份**：`backup.sh` 须在 24 小时内完成，否则备份期间被删除的文件可能已不在卷中。
- **改用 S3 兼容存储**：在服务的覆盖文件中设置 `<ENV>_FILES_STORAGE: s3` 与 `<ENV>_FILES_S3_ENDPOINT`、`<ENV>_FILES_S3_BUCKET`，访问密钥 `<ENV>_FILES_S3_ACCESS_KEY`／`<ENV>_FILES_S3_SECRET_KEY` 写在环境文件中。桶需事先创建。S3 中的数据不在 `backup.sh` 范围内，由对象存储自身的备份或版本机制负责；已有文件不会自动迁移。

## 配置中心

服务的非敏感运行参数由 Nacos 管理（设计、动态项与取值范围见[配置中心](../docs/architecture/05-配置中心.md)）。每个服务一个 dataId `<服务名>.yaml`，种子在 `nacos/config/`：

- `nacos-init` 只在 dataId 不存在时发布种子，之后以 Nacos 中的内容为准，重新部署不覆盖；修改种子不会影响已部署的环境。
- 修改配置：在 Nacos 控制台（本地调试时 `compose.local.yml` 在回环地址发布）以管理员发布。动态项立即生效，其余项重启服务后生效；无法应用的值（越界、类型错误）不生效，服务继续使用当前值并记录 `config_rejected` 日志与 `domainforge.config.refresh{result=rejected}` 指标。
- 服务账号对配置只读；Nacos 中不放凭据与密钥，它们只来自环境文件。
- 服务启动时连不上 Nacos 或 dataId 未发布即启动失败，由 Compose 重启策略重试。
- IAM 的登录防护阈值由 Nacos 管理，环境变量 `IAM_LOGIN_*_ATTEMPTS`／`_WINDOW_SECONDS` 只在未启用配置中心的本地开发中生效。

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
| 首次部署、管理员初始化、普通重启 | 全部 healthy，仅 nginx 有主机端口；`/internal/`、管理端点经入口为 404，歧义路径不转发；重启后账号仍可登录 |
| 网关 | 响应带 traceId（不沿用客户端的追踪头）且见于网关访问日志；网段内直连 IAM（含伪造 Same-Token 访问 `/internal/`）401；同一地址的突发请求被限流，经配置中心调高阈值后实际生效 |
| 备份、备份后继续变更、删除数据库卷、恢复 | 备份前的账号和密码可用，备份后注册的账号与改密不存在，恢复前的会话被拒绝，审计可查询 |
| 升级到带新增迁移的版本 | 自动生成升级前备份，迁移已执行，账号可登录 |
| 应用回退 | 旧版本在新结构上正常运行，新增的表保留 |
| 迁移失败的升级 | 脚本非零退出；失败迁移没有历史记录、没有残留表；回到上一版本后正常 |
| 数据回退 | 用升级前备份恢复到旧版本后，新版本的迁移与表都不存在，账号可登录 |
| 配置中心 | 种子首次发布且 IAM 读取；运行中发布的阈值经入口登录实际生效；重新部署不覆盖；删除 PostgreSQL 与 Nacos 卷后种子重新发布，恢复后配置回到备份时的值 |

新版本由验证脚本在当前镜像上叠加验证专用迁移来模拟，不修改源码。

2026-10-26 本机实测（Apple Silicon，Docker 29.2，初始数据量）：首次启动 21—73 秒（含构建镜像时较长）；备份 1 秒内、约 33 KB；恢复（含 IAM 重启）15—20 秒；升级 9 秒；迁移失败的升级 7 秒内报告失败；数据回退 15 秒。耗时随数据量增长，生产数据量下需在部署环境实测。

## HTTPS

`compose.https.yml` 覆盖文件让 Nginx 在 443 终止 TLS，80 端口只做 301 跳转（健康检查 `/healthz` 除外）；后端仍是 HTTP，网关与后端的鉴权不变（[O-08](../docs/standards/08-配置与运行.md#o-08-tls-在入口终止后端独立鉴权)）。证书由运维提供，任何 CA 签发的都可以（包括在主机上自行运行的 ACME 工具），本参考不负责签发。

```bash
# 1. 证书目录：fullchain.pem（服务器证书在前、中间证书在后）与 privkey.pem；私钥只允许运维用户读取
install -d -m 700 /etc/domain-forge/tls
install -m 644 fullchain.pem /etc/domain-forge/tls/fullchain.pem
install -m 600 privkey.pem /etc/domain-forge/tls/privkey.pem
# 2. 环境文件增加 NGINX_TLS_DIR=/etc/domain-forge/tls、NGINX_HTTPS_BIND=0.0.0.0、NGINX_HTTPS_PORT=443（HTTP 端口通常为 80）
# 3. 之后所有 docker compose 命令与运维脚本都通过 COMPOSE_FILE 列入覆盖文件（有业务服务时一并列入）
export COMPOSE_FILE=deploy/compose.yml:deploy/compose.https.yml
docker compose --env-file /etc/domain-forge/df.env up -d --wait
```

| 方面 | 设置 |
|---|---|
| 协议 | 只接受 TLS 1.2 与 1.3；套件为 Mozilla “Intermediate” 配置的 ECDHE 部分；关闭会话票据 |
| 跳转 | HTTP 上除 `/healthz` 外一律 301 到 `https://<主机>:<NGINX_HTTPS_PORT>`，明文连接上不处理业务请求 |
| HSTS | 默认关闭；确认证书与续期稳定后在环境文件设置 `NGINX_HSTS=max-age=31536000` 并重新 `up`。开启后浏览器在有效期内拒绝降级到 HTTP，证书出错时用户无法绕过 |

**更换证书**：把新的 `fullchain.pem` 与 `privkey.pem` 放入证书目录后执行 `deploy/tls-reload.sh /etc/domain-forge/df.env`。脚本先检查（文件齐全、私钥与证书匹配、证书未过期，30 天内到期时警告），通过后让 Nginx 平滑重载，不断开现有连接；任何一项不通过都不重载，继续使用当前证书。`deploy/tls-reload.sh <环境文件> --check` 只做检查，可用于定时的到期巡检。

**验证**：`scripts/verify_deploy.sh` 最后用测试 CA 签发证书启用覆盖文件，核对：经 HTTPS 登录；HTTP 301 跳转且健康检查不跳转；接受 TLS 1.2、拒绝 TLS 1.1；默认不发送 HSTS，开启后发送；换证书后实际提供新证书且 Nginx 未重启；私钥与证书不匹配时拒绝重载并继续使用当前证书。

## 本地调试

叠加 `compose.local.yml`，在回环地址发布 PostgreSQL、Redis、Nacos（含 gRPC 与控制台）端口，供 IDE 中运行的服务直连：

```bash
docker compose -f deploy/compose.yml -f deploy/compose.local.yml --env-file <环境文件> up -d --wait postgres redis nacos nacos-init
```

生产部署不要叠加该文件。

## 接入业务服务

`platform/templates/ddd-service/generate.py` 生成服务时同时写入：

| 文件 | 内容 |
|---|---|
| `deploy/services/<服务名>.compose.yml` | `<服务名>-db-init`：创建同名数据库账号与 schema（账号只拥有自己的 schema，读不到 IAM 和其他服务的表，每次启动把密码同步为环境文件中的值）；`<服务名>`：镜像构建方式同 IAM，经 Nacos 注册并发现 IAM，健康检查同 IAM；文件存储卷 `<服务名>-files` |
| `deploy/services/<服务名>.archive.compose.yml` | 仅 `--with-example` 时：便笺示例的归档库（附加数据源）。`<服务名>-archive-db-init` 在同一 PostgreSQL 实例中建立属于服务账号的独立数据库 `<schema>_archive`，并为服务提供连接变量；部署时与服务的覆盖文件一起列入 `COMPOSE_FILE` |
| `deploy/nacos/config/gateway.yaml`（追加） | 仅 `--with-example` 时：向网关路由种子追加示例接口的路由。新增公开接口时在这里补充路径，不要写 `/internal/`；已部署的环境改在 Nacos 控制台的 `gateway.yaml` 中补充（种子只在首次部署时发布） |

部署步骤：

```bash
# 1. 环境文件增加本服务数据库账号密码（变量名为服务名大写、- 换成 _），例如
#    NOTE_SERVICE_DB_PASSWORD=<openssl rand -hex 24>
# 2. 构建 IAM、网关与服务的 jar
platform/mvnw -f platform/pom.xml -B clean package -DskipTests -Ddocker.skip=true -pl services/iam,services/gateway,services/note-service -am
# 3. 之后所有 docker compose 命令与 backup.sh／restore.sh／upgrade.sh 都通过 COMPOSE_FILE 列入覆盖文件
# 带示例生成时再列入归档库的覆盖文件 deploy/services/note-service.archive.compose.yml
export COMPOSE_FILE=deploy/compose.yml:deploy/services/note-service.compose.yml
docker compose --env-file /etc/domain-forge/df.env up -d --build --wait
# 4. 由系统管理员把服务的 src/main/resources/iam/permissions.json 安装到 IAM，再建角色授予并分配给账号
```

`restore.sh` 会先运行各服务的 `db-init` 建好账号再导入，因此在新主机或丢失数据卷后也能还原业务表的属主。`upgrade.sh` 只升级 IAM；业务服务按同样方式改 `<ENV>_IMAGE_TAG` 后执行 `docker compose ... up -d --no-build --wait <服务名>`，升级前先运行 `backup.sh`。

**验证**：`scripts/verify_closure.sh`（CI 每次执行）在仓库干净副本中生成带示例的 `note-service` 与以 `--call-example note-service --consume-example note-service` 调用它、消费其事件的 `board-service`，与 IAM 一起按本节启动，经 Nginx 与网关核对：board-service 以同一用户身份读取 note-service 的便笺并受其范围限制、note-service 的无权限判定原样到达、note-service 停机时 503 并在恢复后正常；note-service 创建便笺后 board-service 的便笺统计随事件增加，RocketMQ 停机期间提交的事件留在 Outbox、恢复后继续发送并被消费，备份恢复后统计与便笺一致；临时 Jaeger 中能查到一条贯穿 gateway、note-service、IAM 与 board-service（消息消费）的链路；生成的路由写入网关种子并生效；绕过网关直连业务服务 401；无令牌 401；只需登录的查询共用会话即可通过；未授权的命令 403；安装权限目录、建角色授予（本人范围）并分配后命令成功、范围查询只返回本人数据，未分配角色的账号 403；业务服务数据库账号读 IAM 的表被拒、业务表属于服务账号；经 Nginx 与网关上传 2MB 附件并下载核对摘要、无权限账号下载 403、普通接口仍限 64k；便笺归档写入独立的归档库（属于服务账号、主库中无该表）；备份（含文件卷与归档库）→ 继续写入、上传与重新归档 → 恢复后只剩备份前的数据、附件与归档快照（两个库一致）且服务仍可写入；撤销角色后同一会话立即 403。服务的 IAM 地址留空，以上结果同时证明其经 Nacos 发现了 IAM。
