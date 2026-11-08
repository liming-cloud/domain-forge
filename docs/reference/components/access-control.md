# 访问控制组件（domain-forge-access-control）

在 Sa-Token 原生会话与权限机制上提供默认拒绝、安全错误和后端入口的 Same-Token 校验。规则见[安全与访问控制](../../rules/07-安全与访问控制.md)，IAM 的会话与授权合同见[身份权限](../身份权限.md)。

## 提供什么

| 能力 | 行为 |
|---|---|
| 会话与权限执行 | 直接使用 Sa-Token 原生类／方法注解；令牌只从 `Authorization: Bearer <token>` 读取；会话存于 Redis，使用官方 Redisson DAO，与 [cache](cache.md) 共用同一个 `RedissonClient` |
| 默认拒绝 | 入口未声明访问策略即拒绝，安全失败返回Problem Details（401／403／503，见[错误码](../错误码.md)） |
| Same-Token 校验 | 注册 `SaServletFilter`（顺序 -100，在 Sa-Token 上下文过滤器之后），`setAuth` 中 `SaSameUtil.checkCurrentRequestToken()`，接受当前值与上一个值：缺失或不符 401，会话 Redis 不可用 503。管理端点在独立端口，不经过该过滤器 |
| 开关 | `domainforge.access.same-token.enabled` 默认开启，只允许本地开发与测试关闭；启用 `config-center` profile（部署环境）时关闭会使启动失败 |

Same-Token 的生成、转发与刷新见[API 网关](gateway.md#same-token)，服务间调用的写入见[跨服务调用](service-call.md)。

## 装配边界

- 组件依赖 cache、shared、web-support，不依赖 IAM、Feign、Nacos，也不分本地／远程 `StpLogic` 子类。
- 权限数据由各服务实现 `StpInterface` 提供：IAM 本地实现；其他服务在自己的 `infrastructure/acl/iam` 以 Feign 读取当前用户的权限（`GET /internal/access/current`），失败拒绝、不跨请求缓存允许结果，同一请求内可复用一次。
- 允许本次实际使用的原生权限与角色注解，不额外承诺组合注解或多上下文框架。
