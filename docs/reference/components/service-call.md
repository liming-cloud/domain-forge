# 跨服务调用

服务之间的同步 HTTP 调用统一经 `domain-forge-service-call` 组件（OpenFeign）：身份与链路上下文传递、超时、不重试与失败分类在组件中一次实现，服务只声明 Feign 客户端并翻译成自己的结果。身份传递沿用[API 网关](gateway.md)定下的合同；规则卡见[安全与访问控制 A-14](../../rules/07-安全与访问控制.md#a-14-服务间调用的身份)与[接口与错误 H-09](../../rules/06-接口与错误.md#h-09-服务间调用经跨服务调用组件)。

决定记录：[ADR 0013](../../decisions/0013-跨服务调用的取舍.md)。

## Same-Token 的用法（对照 Sa-Token 官方文档）

[内部服务外网隔离](https://sa-token.cc/doc.html#/micro/same-token)一文给出三处做法，平台按原样采用：

| 环节 | 官方做法 | 平台实现 |
|---|---|---|
| 网关转发 | 全局过滤器为请求加 `SA-SAME-TOKEN: SaSameUtil.getToken()` | 网关 `SameTokenRelayFilter`（先删除客户端自带的同名头） |
| 子服务校验 | 注册 `SaServletFilter`，`setAuth` 中 `SaSameUtil.checkCurrentRequestToken()` | access-control 组件注册该过滤器（顺序 -100，在 Sa-Token 上下文过滤器之后），`setError` 返回 Problem Details：不符 401，存储不可用 503 |
| 服务间调用 | Feign `RequestInterceptor` 加 Same-Token；需要被调方有会话时同时加用户令牌 | 本组件的 `IdentityPropagation` |
| 刷新 | 专门的定时任务按固定间隔 `refreshToken()`，间隔低于有效期；集群中不要多处重复刷新 | 网关每 30 分钟（`domainforge.gateway.same-token-refresh-interval`）在分布式锁内刷新，当前值未满一个间隔则跳过 |

刷新时旧值作为“上一个值”保留到下次刷新，转发途中遇到刷新的请求仍能通过。

## 组件合同

| 方面 | 行为 |
|---|---|
| 请求头 | `SA-SAME-TOKEN` 每次读取当前值（读取失败不发出请求，报 `UNAVAILABLE`）；`Authorization` 取自当前请求，客户端方法已显式声明时不覆盖，没有请求上下文时不带；`traceparent` 由 OpenFeign 的 Micrometer 观测（`feign-micrometer`）发送，见[链路追踪](tracing.md) |
| 超时 | 连接 1 秒、读取 2 秒，不跟随重定向；个别客户端在 `spring.cloud.openfeign.client.config.<contextId>` 覆盖 |
| 实例列表 | 关闭负载均衡层的实例缓存，直接取 Nacos 发现客户端订阅的列表，被调服务重建后不再使用旧地址 |
| 重试 | Feign 与负载均衡层都不重试；是否重试由调用方按接口的幂等合同决定（[F-03](../../rules/09-故障与可观测性.md#f-03-一条调用链只有一处负责重试)） |
| 失败 | 统一抛出 `ServiceCallException`（继承 `DependencyUnavailableException`，调用方不翻译时按 503 返回），分类见下表；消息为固定文本，不带对方的提示、地址或令牌 |
| 成功响应 | 直接是资源，Feign 客户端返回业务类型；对方的失败为 Problem Details，组件读取其 `code` |

| 分类 | 来源 | 含义 |
|---|---|---|
| `UNAUTHENTICATED` | 401 | 对方判定未认证（用户令牌失效，或 Same-Token 不符） |
| `FORBIDDEN` | 403 | 对方判定无权限 |
| `REJECTED` | 其他 4xx | 对方的拒绝，`code()` 为对方代码 |
| `UNAVAILABLE` | 连接失败、无可用实例、5xx（504 除外）、响应缺失 | 请求确定没有被处理 |
| `UNKNOWN` | 读取超时、连接中断、504 | 请求已发出，远端可能已经执行（[S-15](../../rules/02-系统设计.md#s-15-结果未知不能当成功)） |

传输层异常由组件包装底层客户端（Feign `Capability`）直接翻译，不经 Feign 的可重试异常；非 2xx 响应由 `ErrorDecoder` 翻译，只读取响应体前 4 KiB 提取代码。

## 服务中的写法

```
application/port/<对方>/...        端口：技术无关的读取或命令，失败以本服务的语义报告
infrastructure/acl/<对方>/...      Feign 客户端（只声明路径与参数）＋ 端口实现（失败翻译）
```

- 以当前用户身份调用时，调用方入口至少要求登录；能读到什么由被调方按同一用户的权限与范围决定，调用方不放宽。
- 对方的 401、403 一般保持语义；`UNAVAILABLE` 与 `UNKNOWN` 对读取都可按依赖不可用（503）处理，对写入不能报告成功或失败，按[S-15](../../rules/02-系统设计.md#s-15-结果未知不能当成功)核对结果。
- 跨服务调用不在本地事务回滚范围内（[S-12](../../rules/02-系统设计.md#s-12-跨资源跨服务没有原子性)）；需要可靠的跨服务副作用时用[消息与 Outbox](messaging.md)。
- 服务专用接口放在 `/internal/**` 并以 `@SaIgnore` 声明；它没有用户身份，不能依赖当前用户，也不能接受调用方提交的账号或权限字段作为授权依据。
