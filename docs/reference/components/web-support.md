# Web 组件（domain-forge-web-support）

统一失败分派与 Problem Details 响应。规则见[接口与错误规范](../../rules/06-接口与错误.md)。

## 接入

1. 服务依赖 `shared`、`domain-forge-web-support` 和 `spring-boot-starter-webmvc`（版本由 `domain-forge-bom` 管理）。
2. 在 Controller 上标注 `@PlatformJsonApi`；只有标注的入口才由组件处理错误。
3. Controller 直接返回资源，没有响应体时用 `@ResponseStatus(HttpStatus.NO_CONTENT)`；失败直接抛出规则违反或依赖不可用类型，不构造响应。

组件只在 Servlet MVC 应用中自动装配，不需要配置项。

## 公开接口

| 类型 | 用途 |
|---|---|
| `org.domainforge.shared.BusinessRuleViolation` | 业务拒绝的基类：带类别（`Kind`）、稳定错误码和可展示的提示。领域层与应用层各自继承 |
| `org.domainforge.shared.DependencyUnavailableException` | 依赖不可用的基类，返回 503，消息与原因只写日志 |
| `org.domainforge.web.api.PlatformJsonApi` | 标记接入组件的 Controller |
| `org.domainforge.web.api.Problems` | 唯一分派点：`dispatch` 按基类得到响应；`of`、`write`、`json` 供自带处理器与 Filter 写出同样的 Problem Details |
| `org.domainforge.web.api.FailureTranslator` | 平台组件把第三方原生异常转为上述基类，经 `META-INF/services` 登记（业务不需要） |

`org.domainforge.web.internal` 和装配包不是公开接口，架构规则禁止其他模块引用。

## 分派规则

| 失败 | 状态 | `code` |
|---|---|---|
| `BusinessRuleViolation` | 由 `Kind` 决定：400、401、403、404、409、413、422、429 | 异常自带 |
| `DependencyUnavailableException` | 503 | `SERVICE_UNAVAILABLE` |
| 请求体无法解析、参数类型不符、multipart 格式错误 | 400 | `INVALID_REQUEST` |
| 上传超过 multipart 上限 | 413 | `PAYLOAD_TOO_LARGE` |
| 其他异常 | 500 | `INTERNAL_ERROR` |

完整清单见[错误码](../错误码.md)。同一个已标注的 Controller 不要再装自己的兜底 `@ControllerAdvice`，启动时会被拒绝。

## 诊断日志

503 与 500 各记录一条 ERROR 日志：固定分类、异常类型、原因类型和代码位置（最多 4 层原因，每层 8 个位置，总计不超过 4096 字符，超出时标注截断）。日志不记录异常 message、请求头或请求体，响应中也不返回内部信息。
