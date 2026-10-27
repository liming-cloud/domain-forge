# API 网关（P6-3 设计）

新增服务 `gateway`，位于 Nginx 与各后端服务之间，负责路由、登录前置校验、限流与请求标识。后端仍各自认证和授权（[O-08](../standards/08-配置与运行.md#o-08-tls-在入口终止后端独立鉴权)），网关不替代 IAM 的当前授权判断。选型与验收见[路线图](../product/02-路线图.md#p6-实施计划)。本页为设计，尚未实施。

## 已确认的决定（2026-10-27）

| 问题 | 决定 |
|---|---|
| 身份传递合同 | 原样转发 `Authorization: Bearer <token>`。网关只做登录前置校验，后端继续按“原生会话＋SQL 许可＋当前授权”判定（[身份设计](04-身份权限.md#sql-与会话状态的生效合同)），撤权、改密、禁用立即生效的语义不变；不签发内部凭据 |
| 防止绕过网关直连后端 | Sa-Token Same-Token＋网络隔离：网关转发和服务间调用都携带 Same-Token，后端统一校验，缺失或不符即拒绝；后端仍不暴露主机端口 |
| 路由来源 | 网关的 Nacos 配置集中维护路由；仓库维护种子，模板生成服务时向种子追加路由 |
| Nginx 路由迁移 | Nginx 只做边缘：整个 `/api/` 转给网关，只负责请求体上限、`/healthz` 和后续 HTTPS；删除 `deploy/nginx/services/`，模板改为生成网关路由 |

## 请求路径

```
客户端 → Nginx（边缘）→ gateway（路由、登录前置、限流、请求标识）→ lb://<服务>（Nacos 发现）→ 后端（Same-Token、认证、授权）
后端 A → Feign → lb://<服务 B>（携带 Same-Token 与用户令牌，不经网关）
```

| 层 | 负责 | 不负责 |
|---|---|---|
| Nginx | 唯一公开端口、请求体上限、`/healthz`、生成请求标识、用 `$remote_addr` 覆盖 `X-Forwarded-For`、后续 TLS | 按服务分路由（改由网关负责） |
| gateway | 按路由转发、拒绝 `/internal/` 与管理端点、登录前置校验、按客户端地址限流、写入 Same-Token、透传请求标识、统一网关错误响应 | 权限与数据范围判断、业务错误转换 |
| 后端 | 校验 Same-Token；认证、授权、数据范围；业务规则 | 信任网关的登录结论 |

## 网关服务

- 位置 `platform/services/gateway`，同样遵守五个职责包和架构规则；网关没有领域模型，只有 `bootstrap` 与 `infrastructure`（路由装配、过滤器、错误响应）。
- 技术：Spring Cloud Gateway Server WebFlux（随现有 Spring Cloud BOM），`sa-token-reactor-spring-boot4-starter` 与官方 Redisson DAO，Nacos 服务发现与配置（复用 `domain-forge-config`）。网关是 WebFlux 应用，不依赖 Servlet 版的 `domain-forge-web-support`、`domain-forge-access-control`。
- 不连接 PostgreSQL；只用 Redis（会话查询、限流、Same-Token）和 Nacos。

### 路由配置

路由是网关 dataId `gateway.yaml` 中的一个动态配置组 `gateway`，用 `DynamicSettingsBinder` 整组绑定（[配置中心](05-配置中心.md#接入方式)）：发布值无法应用时保持当前路由，修正后生效。不直接使用 Spring Cloud Gateway 的 `spring.cloud.gateway.server.webflux.routes`：那里允许任意谓词和过滤器，非法定义只丢弃单条路由，与“整组不应用”的规则（[O-09](../standards/08-配置与运行.md#o-09-nacos-发布内容是权威)）不一致。

```yaml
gateway:
  routes:
    - id: iam
      service: iam                 # 转发到 lb://iam
      paths: [/api/**]             # 公开前缀
      anonymous: [POST /api/auth/login, POST /api/auth/invitations/accept]
    - id: note-service
      service: note-service
      paths: [/api/notes, /api/notes/**, /api/me/notes]
  rate-limit:
    replenish-per-second: 20       # 每个客户端地址
    burst: 40
```

- 每条路由只有服务名、路径前缀与匿名清单，不开放任意过滤器；组件按受限结构生成路由定义，应用成功后发布 `RefreshRoutesEvent`。
- 校验（启动时阻止启动，运行时按无法应用处理）：`id` 唯一；`service` 为合法服务名；路径以 `/api/` 开头、不含 `/internal/`；同一路径不能出现在两条路由；匿名项必须落在本路由路径内。
- 匹配按路径前缀最长优先，与 Nginx 现状一致（业务服务的前缀比 IAM 的 `/api/**` 更具体）。
- 不在路由中的路径一律 404；`/internal/**`、`/actuator/**` 无论配置如何都 404。
- 种子 `deploy/nacos/config/gateway.yaml`：模板生成服务时追加该服务的路由。种子只在 dataId 不存在时发布（[配置中心](05-配置中心.md#已确认的决定2026-10-27)），已部署环境新增服务时需要在 Nacos 控制台补上路由，部署参考写明此步骤。

### 登录前置校验

- 不在匿名清单中的请求，网关用 Sa-Token 校验原生会话存在；缺少令牌或会话不存在返回 401。这只是提前拒绝，后端仍做完整认证（含 SQL 许可与凭据版本），网关放行不代表已认证。
- 令牌只从 `Authorization` 头读取（[A-04](../standards/07-安全与访问控制.md#a-04-令牌只从-authorization-头读取)），原样转发，不改写、不记录。
- Redis 不可用时返回 503，不放行（[A-03](../standards/07-安全与访问控制.md#a-03-依赖故障时拒绝不降级放行)）。

### Same-Token

- 使用 Sa-Token 原生 Same-Token（请求头 `SA-SAME-TOKEN`，值保存在共用 Redis，由 Sa-Token 生成与轮换），不自造密钥或签名协议。能读到该 Redis 的进程本来就能读到全部会话，信任边界不扩大。
- 网关对每个转发请求写入当前 Same-Token，并删除客户端传入的同名头。
- 后端校验由 `domain-forge-access-control` 提供：所有请求先校验 Same-Token（接受当前值与轮换前的上一个值），不符返回 401；`/actuator/health/**` 例外，供容器健康检查使用。组件默认开启，关闭需显式配置，部署环境不得关闭（启动校验）。
- 服务间调用（当前为业务服务调用 IAM 的 `/internal/access/*`）由调用方在 Feign 请求中加上 Same-Token；P6-4 跨服务调用组件接管这一步。
- 轮换：由网关定时调用刷新，轮换窗口内新旧值都有效；实施时用真实 Redis 验证轮换期间的请求不失败。

### 限流

- Redis 令牌桶，键为客户端地址（取自 Nginx 写入的 `X-Forwarded-For`，网关只信任 Nginx 固定地址给出的该头），超限返回 429；参数在 `gateway.rate-limit` 中动态调整。
- 这是过载保护，不是安全边界。Redis 不可用时限流放行并记录错误与指标；此时受保护路由已因登录前置校验返回 503，匿名的登录接口仍受 IAM 登录防护约束（Redis 不可用即拒绝），因此不会因此放开暴力尝试。
- 按账号限流、按路由区分阈值不在本轮范围，出现真实需求时再加。

### 请求标识

- Nginx 生成 `X-Request-Id`（`$request_id`），覆盖客户端传入的值；网关缺少该头时自行生成（本地开发不经 Nginx 时）。
- 网关原样向后端透传，并写入网关访问日志；后端由 `domain-forge-web-support` 写入日志上下文（MDC）并在响应头返回；服务间调用由 P6-4 组件继续传递。
- 只用于关联日志，不作为幂等键或安全依据。

### 客户端地址

后端目前只信任 Nginx 固定地址给出的 `X-Forwarded-For`（IAM 登录防护按它计数）。引入网关后：网关信任 Nginx 固定地址并原样转发该头，后端的可信代理改为网关的固定地址（`SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`），Nginx 地址不再直连后端。

### 错误响应

网关自身产生的错误使用与 [Web 组件](03-Web组件.md#结果映射)相同的三字段外壳，后端响应原样返回、不改写。

| 情况 | HTTP 状态 | `code` |
|---|---|---|
| 路由不存在、`/internal/`、管理端点 | 404 | 保持框架默认，不包装 |
| 未登录（前置校验） | 401 | `UNAUTHENTICATED` |
| 超出限流 | 429，带 `Retry-After` | `RATE_LIMITED`（与 IAM 登录防护的 `LOGIN_RATE_LIMITED` 区分） |
| 无可用实例、连接失败、Redis 不可用 | 503 | `SERVICE_UNAVAILABLE` |
| 下游响应超时 | 504 | `GATEWAY_TIMEOUT` |

新增的 `RATE_LIMITED`、`GATEWAY_TIMEOUT` 作为平台保留代码，与现有保留代码一起由 `ApiResponse` 拒绝业务占用，并在 [Web 组件](03-Web组件.md#结果映射)登记。超时沿用 Nginx 现值：连接 2 秒、响应 15 秒（Nginx 的读超时相应放宽，保证由网关先超时并给出 504）。

## 部署

- Compose 新增 `gateway`（固定内网地址，无主机端口，依赖 Redis、Nacos、`nacos-init` 健康），注册到 Nacos；Nginx 只转发到网关，依赖网关健康。
- `deploy/nginx/default.conf` 的 `/api/` 改为转发到网关，删除 `deploy/nginx/services/` 与模板中的对应文件；备份恢复、升级脚本中“停止／启动 nginx、iam”的步骤加入网关。
- 后端服务（IAM 与模板生成的服务）的可信代理改为网关地址，并开启 Same-Token 校验。
- 网关无持久数据，不进入备份范围；路由随 Nacos 配置备份恢复。

## 验证计划

| 场景 | 方式 | 判定 |
|---|---|---|
| 正常转发 | `GatewayIT`：真实网关＋Redis＋开启鉴权的 Nacos＋桩后端服务 | 按最长前缀转到对应服务；令牌、请求标识、客户端地址原样到达后端 |
| 未登录拒绝 | 无令牌、无效令牌、只在参数中给令牌 | 401；匿名清单内的路径放行 |
| 限流 | 超过桶容量的连续请求 | 429；动态调小阈值后按新值生效 |
| 下游故障 | 后端未注册、停止、超时 | 503、503、504 |
| 路由动态更新 | 运行中发布新路由、非法路由 | 新路由生效；非法发布保持原路由并计 `rejected` |
| 禁止路径 | `/internal/access/current`、`/actuator/env` | 404，即使路由前缀覆盖 |
| 直连后端被拒 | access-control 集成测试：不带、带错误、带轮换前的 Same-Token | 前两者 401，后者在轮换窗口内通过；健康检查端点不受影响 |
| 部署闭环 | `verify_deploy.sh`、`verify_closure.sh` 改为经 Nginx→网关；增加从网段内其他容器直连后端 | 现有场景全部通过；直连后端 401；只有 Nginx 有主机端口 |

## 实施步骤

1. 后端 Same-Token 校验与请求标识：access-control、web-support 组件，IAM 与模板的 Feign 调用携带 Same-Token；组件测试。
2. gateway 服务：路由配置组、登录前置、限流、错误响应、`GatewayIT`。
3. 部署与模板：Compose、Nginx 收敛、种子、可信代理、场景验证与部署参考。

每步一个 PR，以评审负担决定是否合并。
