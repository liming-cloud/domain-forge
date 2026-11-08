# API 网关

新增服务 `gateway`，位于 Nginx 与各后端服务之间，负责路由、登录前置校验、限流，并作为链路追踪的起点。后端仍各自认证和授权（[O-08](../../rules/08-配置与运行.md#o-08-tls-在入口终止后端独立鉴权)），网关不替代 IAM 的当前授权判断。部署与运维见[部署参考](../../../deploy/README.md#api-网关)。

决定记录：[ADR 0012](../../decisions/0012-API网关的取舍.md)。

## 请求路径

```
客户端 → Nginx（边缘）→ gateway（路由、登录前置、限流、链路起点）→ lb://<服务>（Nacos 发现）→ 后端（Same-Token、认证、授权）
后端 A → Feign → lb://<服务 B>（携带 Same-Token 与用户令牌，不经网关）
```

| 层 | 负责 | 不负责 |
|---|---|---|
| Nginx | 唯一公开端口、请求体上限、`/healthz`、删除客户端带来的追踪头、用 `$remote_addr` 覆盖 `X-Forwarded-For`、后续 TLS | 按服务分路由（改由网关负责） |
| gateway | 拒绝可能被后端规范化成其他路径的请求、按路由转发、登录前置校验、按客户端地址限流与按路由的接口限流、写入 Same-Token、开启链路并返回 traceId、统一网关错误响应 | 权限与数据范围判断、业务错误转换 |
| 后端 | 校验 Same-Token；认证、授权、数据范围；业务规则 | 信任网关的登录结论 |

## 网关服务

- 位置 `platform/services/gateway`，遵守五个职责包和架构规则；网关没有领域模型，只有 `bootstrap`（启动与装配）与 `infrastructure`（`routing` 路由配置、`filter` 过滤器与错误响应、`security` Same-Token 刷新）。
- 技术：Spring Cloud Gateway Server WebFlux 5.0（随 Spring Cloud BOM），`sa-token-reactor-spring-boot4-starter` 与官方 Redisson DAO，Nacos 服务发现与配置（复用 `domain-forge-config`），Redis 客户端、Lua 脚本与分布式锁复用 `domain-forge-cache`。网关是 WebFlux 应用，不依赖 Servlet 版的 `domain-forge-web-support`、`domain-forge-access-control`；会话与 Same-Token 的 Redis 键名由 `sa-token.token-name` 决定，网关配置必须与 access-control 组件一致（`Authorization`）。
- 不连接 PostgreSQL；只用 Redis（会话查询、限流、Same-Token）和 Nacos。Redis 读写都是阻塞调用，在 `boundedElastic` 线程上执行，不占用事件循环。
- 端口：业务 8000，管理 9093（只在内部网络可达，开放健康检查与 `/actuator/prometheus`）。
- 实例列表直接取 Nacos 发现客户端（订阅变更推送），关闭 Spring Cloud LoadBalancer 的实例缓存（默认 35 秒）：否则后端重建换地址后，旧地址仍会被使用 35 秒（CI 升级场景中曾因此登录失败）。

请求在网关内的处理顺序：

| 顺序 | 步骤 | 失败结果 |
|---|---|---|
| 1 | 路径检查（路由之前） | 400 `INVALID_REQUEST` |
| 2 | 客户端地址；删除客户端自带的 `Forwarded`、`X-Real-IP`、`SA-SAME-TOKEN` | — |
| 3 | 限流（全部请求） | 429 `RATE_LIMITED` |
| 4 | 登录前置校验（Sa-Token `SaReactorFilter`，匿名清单除外）；通过后记下账号 | 401 `UNAUTHENTICATED`；Redis 不可用 503 |
| 5 | 接口限流（路由声明的 `limits`，按账号或客户端地址） | 429 `RATE_LIMITED` |
| 6 | 路由匹配 | 404（框架默认） |
| 7 | 写入 Same-Token | Redis 不可用 503 |
| 8 | 转发；最后一步写入 `X-Forwarded-For` | 无实例或连接失败 503，超时 504 |

### 路由配置

路由是网关 dataId `gateway.yaml` 中的动态配置组 `gateway.routing`，用 `DynamicSettingsBinder` 整组绑定（不直接用 `gateway` 作前缀：部署环境变量 `GATEWAY_*` 按宽松绑定映射到 `gateway.*`，会被整组绑定当作未知键；见[配置中心](config.md#接入方式)）：发布值无法应用时保持当前路由并计 `rejected`，修正后生效。不直接使用 Spring Cloud Gateway 的 `spring.cloud.gateway.server.webflux.routes`：那里允许任意谓词和过滤器，非法定义只丢弃单条路由，与“整组不应用”的规则（[O-09](../../rules/08-配置与运行.md#o-09-nacos-发布内容是权威)）不一致。

```yaml
gateway:
  routing:
    rate-limit:
      replenish-per-second: 20     # 每个客户端地址每秒补充的令牌，1—10000
      burst: 40                    # 桶容量，不小于补充速率
    routes:
      - id: iam
        service: iam               # 转发到 lb://iam（Nacos 发现）
        paths: [/api/**]           # 公开路径模式
        anonymous: [POST /api/auth/login, POST /api/auth/invitations/accept]
      - id: order-service
        service: order-service
        paths: [/api/orders/**, /api/me/orders]
        limits:                    # 可选：接口限流，按顺序取第一条命中的
          - endpoint: POST /api/orders/*/files   # “方法 路径模式”，须落在本路由的路径内
            by: account            # account 按账号；client 按客户端地址
            replenish-per-minute: 30   # 1—600000
            burst: 10              # 1—100000
```

- 每条路由只有服务名、路径、匿名清单与接口限流，不开放任意过滤器。
- 校验（启动时阻止启动，运行时按无法应用处理）：`id` 唯一；`service` 为小写服务名；路径以 `/api/` 开头，不含 `internal` 段、`..` 或空段，且是合法的路径模式；同一路径不能出现在两条路由；匿名项为“方法 确切路径”，方法限 GET、POST、PUT、PATCH、DELETE，路径必须落在本路由的路径内；限流项的方法同上、路径模式落在本路由的路径内、维度为 `account` 或 `client`、速率与容量有界；`rate-limit` 有界。
- 匹配：每个路径单独生成一条路由，按第一个通配或变量之前的字面前缀长度排序，越具体越先匹配（业务服务的前缀优先于 IAM 的 `/api/**`）。按“路由”整体排序会让包含多个路径的路由抢先匹配，因此按路径拆开。
- 不在路由中的路径一律 404；路径必须以 `/api/` 开头，因此 `/internal/**`、`/actuator/**` 不可能被路由。
- 生效：配置组重新绑定后，Spring Cloud 的刷新事件触发网关重新读取路由；`GatewayIT` 以“发布后请求实际转到新服务”判定，不以收到通知为准（[O-10](../../rules/08-配置与运行.md#o-10-收到通知不等于已生效)）。
- 种子 `deploy/nacos/config/gateway.yaml`：模板以 `--with-example` 生成服务时追加示例接口的路由。种子只在 dataId 不存在时发布（[ADR 0011](../../decisions/0011-配置中心的取舍.md)），已部署环境新增服务时需要在 Nacos 控制台补上路由。

### 路径检查

网关按原始路径匹配路由并原样转发，后端 Tomcat 会解码并规范化路径：`/api/%2e%2e/internal/access/current` 能命中 `/api/**`，到后端却落到 `/internal/`，而网关已为它写入 Same-Token。因此在路由之前拒绝（400）以下写法，不尝试“安全地规范化”：编码的点号、斜杠、反斜杠与百分号（`%2e`、`%2f`、`%5c`、`%25`）、`%00`、`;`（路径参数）、反斜杠、空段（`//`）、`.` 与 `..` 段。Nginx 先解码再匹配 location，这类请求多数在 Nginx 已为 404，网关这一层不依赖 Nginx 的行为。

### 登录前置校验

- 按 Sa-Token 官方[网关统一鉴权](https://sa-token.cc/doc.html#/micro/gateway-auth)用法注册 `SaReactorFilter`：`setAuth` 中以 `SaRouter.match(不在任一路由的匿名清单中).check(StpUtil.checkLogin())` 校验，`setError` 返回 Problem Details。缺少令牌或会话不存在返回 401（未路由的路径对未登录请求同样是 401）。这只是提前拒绝，后端仍做完整认证（含 SQL 许可与凭据版本），网关放行不代表已认证。
- 原生过滤器在事件循环线程上同步读取 Redis（Sa-Token 响应式集成的既定方式），等待由 Redis 命令超时（300 毫秒）限定；建立连接的超时为 2 秒，只在启动与重连时生效（CI 上多个服务同时启动时，300 毫秒内曾建不起初始连接池，网关反复重启）。
- 令牌只从 `Authorization` 头读取（[A-04](../../rules/07-安全与访问控制.md#a-04-令牌只从-authorization-头读取)），原样转发，不改写、不记录。
- Redis 不可用时返回 503，不放行（[A-03](../../rules/07-安全与访问控制.md#a-03-依赖故障时拒绝不降级放行)）。

### Same-Token

- 使用 Sa-Token 原生 Same-Token（请求头 `SA-SAME-TOKEN`，值保存在共用 Redis 的 `Authorization:var:same-token`），不自造密钥或签名协议。能读到该 Redis 的进程本来就能读到全部会话，信任边界不扩大。
- 网关对每个转发请求写入当前值（每次从 Redis 读取，不缓存），客户端传入的同名头在此之前已删除；读取失败返回 503，不省略请求头继续转发。
- 后端校验按 Sa-Token 官方用法由 `domain-forge-access-control` 注册的 `SaServletFilter` 完成（`setAuth` 中 `SaSameUtil.checkCurrentRequestToken()`，接受当前值与上一个值）：缺失或不符返回 401，会话 Redis 不可用返回 503。管理端点在独立端口，不经过该过滤器，容器健康检查不受影响。官方用法与平台实现的逐项对照见[跨服务调用](service-call.md#same-token-的用法对照-sa-token-官方文档)。
- 开关 `domainforge.access.same-token.enabled` 默认开启，只允许本地开发与测试关闭；启用 `config-center` profile（即部署环境）时关闭会使启动失败。
- 服务间调用由[跨服务调用组件](service-call.md)的 Feign 拦截器写入 Same-Token。
- 刷新：按官方建议由专门的定时任务按固定间隔调用 `SaSameUtil.refreshToken()`。网关启动时立即检查一次，此后每 30 分钟（`domainforge.gateway.same-token-refresh-interval`，低于 24 小时的有效期 `sa-token.same-token-timeout`）刷新；多个网关实例在同一把分布式锁内检查当前值的“年龄”，未满一个间隔说明其他实例刚刷新过，跳过——官方文档提醒集群中不要多处重复刷新。旧值作为“上一个值”保留到下次刷新。
- 首个值：网关启动时生成。若某个后端先于网关调用 `SaSameUtil.getToken()`，由它生成；两个进程同时首次生成时其中一个值会被覆盖，持有它的那次请求被拒——只在首次生成时可能发生，网关定时刷新使值不会过期。

### 限流

- 对全部请求生效、先于登录校验，未登录请求的洪泛也受限，不会全部压到会话查询。Redis 令牌桶（Lua 脚本，键 `gateway:rate:<客户端地址>`），用 Redis 服务器时间计算补充量，避免多个网关实例的时钟差；超限返回 429 与 `Retry-After`。参数每次请求读取当前配置，动态调整立即生效。
- 这是过载保护，不是安全边界。Redis 不可用时限流放行，记录错误日志与计数器 `domainforge.gateway.rate-limit.unavailable`（供告警）；此时会话查询与 Same-Token 读取同样失败，请求仍以 503 结束，不会因此放开暴力尝试。
- 接口限流（[#53](https://github.com/liming-cloud/domain-forge/issues/53)）：路由可为具体接口声明令牌桶，取请求实际会转到的路由（字面前缀最长）中第一条命中的规则。`by: account` 按已通过登录校验的账号计数，换客户端地址不能绕过；接口在匿名清单中时没有账号，按客户端地址计数。`by: client` 按客户端地址计数。键为 `gateway:route-rate:<路由>:<规则序号>:account:<账号>`／`…:client:<地址>`，与全局桶一样在 Redis 中、多个网关实例共享；规则随配置动态生效，超限 429 `RATE_LIMITED` 与 `Retry-After`（补充一个令牌所需秒数），计数器 `domainforge.gateway.route-limit.rejected`（标签 `route`、`by`）。Redis 不可用时同样放行并计 `unavailable`。
- 接口限流排在登录校验之后：未登录的请求已在登录校验处 401，不会消耗账号的配额；全局按地址限流仍在登录校验之前，挡住未登录请求的洪泛。
- 只在网关按路由声明，不提供服务内的限流注解；业务自定义键（如按手机号）不在本项范围。

### 链路标识

- 网关为每个请求开启链路的根 span（Nginx 已删除客户端带来的追踪头），向后端发送 `traceparent`，在响应头 `X-Trace-Id` 返回 traceId，并写访问日志 `gateway_access trace_id=… method=… path=… status=… route=… millis=…`。
- 后端响应中的 `X-Trace-Id` 在复制前删除，响应只带网关写入的一个值。传播、采样与导出见[链路追踪](tracing.md)。
- 只用于关联日志与链路，不作为幂等键或安全依据。

### 客户端地址

- 网关只采用可信代理（`GATEWAY_TRUSTED_PROXIES`，部署中为 Nginx 固定地址）给出的 `X-Forwarded-For` 的最后一项，格式不像 IP 地址或连接不来自可信代理时用连接地址。
- 转发时以该地址覆盖 `X-Forwarded-For`。Spring Cloud Gateway 在未配置其自身可信代理时会删除全部 `X-Forwarded-*` 请求头，因此由一个排在最后的请求头过滤器写入，并关闭框架的转发头追加。
- 后端的可信代理为网关的固定地址（`SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`），IAM 登录防护仍按真实客户端地址计数；Nginx 不再直连后端。

### 错误响应

网关自身产生的错误使用与 [Web 组件](web-support.md)相同的 Problem Details（`application/problem+json`，错误码在 `code`），后端响应原样返回、不改写；日志只记录异常类型。

各情况的状态码与 `code` 见[错误码](../错误码.md)；`RATE_LIMITED`、`GATEWAY_TIMEOUT` 是平台保留代码，不得被业务占用。超时：连接 2 秒、响应 15 秒（`GATEWAY_RESPONSE_TIMEOUT`）；Nginx 的读超时放宽到 20 秒，保证由网关先超时并给出 504。
