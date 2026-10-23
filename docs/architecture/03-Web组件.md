# Web 组件（domain-forge-web-support）

统一 JSON 响应外壳和错误转换。外壳格式规则见[接口与错误规范](../standards/06-接口与错误.md)。

## 接入

1. 服务依赖 `shared`、`domain-forge-web-support` 和 `spring-boot-starter-webmvc`（版本由 `domain-forge-bom` 管理）。
2. 在 Controller 上标注 `@PlatformJsonApi`；只有标注的入口才由组件处理错误。
3. Controller 显式返回外壳：`ApiResponse.success(data)`、`ApiResponse.rejected(code, message)` 或 `HttpResponses.accepted(data)`。

组件只在 Servlet MVC 应用中自动装配，不需要配置项。

## 公开接口

| 类型 | 用途 |
|---|---|
| `org.domainforge.shared.ApiResponse<T>` | 三字段结果 `code`、`message`、`data`；`data` 无值时也输出 `null`。领域和应用层不返回它，由入口转换 |
| `org.domainforge.web.api.PlatformJsonApi` | 标记接入组件的 Controller |
| `org.domainforge.web.api.HttpResponses` | 仅受理（HTTP 202 + `ACCEPTED`） |
| `org.domainforge.web.api.BusinessRejectionMapping<E>` | 把一个**确切的**业务异常类型映射为业务拒绝 |
| `org.domainforge.web.api.HttpFailureResponses` | 技术适配层显式构造 401、403、503 等失败响应 |

`org.domainforge.web.internal` 和装配包不是公开接口，架构规则禁止其他模块引用。

## 结果映射

| 场景 | HTTP 状态 | `code` |
|---|---|---|
| 成功（含查询无结果） | 200 | `SUCCESS` |
| 已受理、尚未完成 | 202 | `ACCEPTED` |
| 已判定的业务拒绝 | 200 | 业务自定义的稳定代码，`data` 为 `null` |
| 请求体或参数解析失败 | 400 | `INVALID_REQUEST` |
| 未认证 / 无权限 | 401 / 403 | `UNAUTHENTICATED` / `FORBIDDEN` |
| 依赖不可用 | 503 | `SERVICE_UNAVAILABLE` |
| 未知异常、映射失败 | 500 | `INTERNAL_ERROR`，固定安全提示 |
| 路由不存在、方法不允许 | 保持框架默认（404 / 405） | 不改写 |

`SUCCESS`、`ACCEPTED` 和上述平台失败代码是保留代码，业务拒绝不能使用。二进制、流式响应和 Actuator 端点不包装。

## 业务拒绝映射

- 每个 `BusinessRejectionMapping` 只声明一个确切异常类型，不按继承关系或异常名称匹配。
- 同一类型重复声明，或声明 `Exception`、`RuntimeException` 这类通用基类，启动时直接失败。
- 未注册的异常按未知故障处理（500）。
- 业务异常不需要继承平台类型。
- 同一个已标注的 Controller 不要再装自己的兜底 `@ControllerAdvice`，否则会和组件冲突。

## 未知故障的诊断日志

未知故障记录一条 ERROR 日志：固定分类、异常类型、原因类型和代码位置（最多 4 层原因，每层 8 个位置，总计不超过 4096 字符，超出时标注截断）。日志不记录异常 message、请求头或请求体，响应中也不返回内部信息。
