# API 与公共契约

状态：当前协议与行为已接受（DESIGN-v0.1 / H02）；未写出的字段级约束仍待细化。本文只定义协议与行为，JSON 是设计示例，不是运行结果；不生成 Controller、SDK 或 OpenAPI 工程。领域规则只在[安全模型](03-iam-domain-security.md)维护。

## 1. 协议约定

- 公开接口前缀 `/api/v1/iam`，资源 ID 使用不透明字符串，时间采用带时区的 ISO 8601。
- 使用 HTTPS 与 `Authorization: Bearer <opaque-token>`。Token 不放 URL，不记录在日志；首批不自动使用 Cookie 身份模式。
- 成功返回资源 DTO，创建返回 201，读取/更新返回 200，无响应体的删除/退出返回 204。错误使用真实 HTTP 状态和统一错误对象。
- 分页 `page` 从 1 开始，`size` 默认 20、最大 100；排序仅允许资源明确定义的字段，并追加 ID 稳定排序。`total` 必须应用相同权限范围。
- 修改管理配置使用 `expectedVersion` 防止静默覆盖。账号口令、角色绑定、安全状态等通过独立命令处理，不允许通用 PATCH 写任意属性。
- 没有独立操作码的身份自身接口只返回当前主体；一般受保护操作必须校验固定权限点，不采用客户端传入的“所需权限”。

## 2. API 分组与最小职责

以下路径族与职责已接受；`*` 为文档中的路径族，不能据此实现通配放行。

| ID | 方法与路径（相对前缀） | 输入/输出重点 | 授权策略 | 领域场景 |
|---|---|---|---|---|
| API01 | POST `/auth/login` | username、password → Token、到期时间 | 公开入口，执行凭据验证与登录限流 | S01 |
| API02 | POST `/auth/logout` | 当前 Token → 204 | 撤销当前会话，不接收任意 userId | S01 |
| API03 | GET `/auth/session`、GET `/me` | 当前主体最小信息 | 仅当前有效会话 | S01 |
| API04 | PUT `/me/password` | 原密码、新密码 → 204 | 当前主体；成功后使旧凭据版本会话失效 | S01 |
| API05 | GET `/auth/authorization?permission=...` | 固定操作标识 → 当前主体范围 | IAM 只判定当前 Token 主体；不支持代查任意用户 | S04 |
| API06 | GET `/users`、GET `/users/{id}` | 分页/用户概要 | `iam:user:read` + 目标用户范围；超管按 IAM 管理策略 | S04 |
| API07 | POST `/users`；PUT `/users/{id}/department`；PUT `/users/{id}/status`；POST `/users/{id}/sessions/revoke` | 创建、调部门、禁用/启用、撤销全部会话分别建模 | 仅超管；保护最后超管；不提供用户硬删除接口 | S01、S03、S05 |
| API08 | GET/POST `/roles`；GET/PUT/DELETE `/roles/{id}` | 角色目录及元信息/状态；删除需无引用 | 仅超管 | S02 |
| API09 | PUT `/users/{id}/roles`；PUT `/roles/{id}/grants` | 完整替换绑定/权限及范围，带 expectedVersion | 仅超管，不支持客户端写 isSuperAdmin | S02、S05 |
| API10 | GET/POST `/departments`；PUT/DELETE `/departments/{id}` | 部门树、移动节点和删除空叶子 | 仅超管；引用与无环校验 | S03 |
| API11 | GET/POST `/permissions`；PUT/DELETE `/permissions/{id}`；GET/POST `/resources`；PUT/DELETE `/resources/{id}` | 权限目录与导航配置；引用中的权限点不可删除或改码 | 仅超管；权限目录不动态创造服务端实现 | S02、S06 |
| API12 | GET `/me/navigation`；GET `/audits` | 当前用户菜单树；管理审计分页 | 前者当前会话 + 可见权限；后者仅超管 | S06、S05 |

初始化超级管理员使用受控离线命令的设计，不提供公开 bootstrap HTTP 入口。与账号创建有关的初始凭据通过一次性安全渠道交付；H02 接受由超管通过受控渠道一次性交付的方向；具体操作与字段在 PLAN-02 细化，不把口令写进 GET 响应。

## 3. 请求响应示例

### 登录（API01）

请求：

```json
{"username":"example-user","password":"<example-secret-not-a-default>"}
```

成功响应，过期时长采用安全模型中的已接受政策；示例时间不作为固定产品参数：

```json
{"tokenType":"Bearer","accessToken":"<opaque-token>","expiresAt":"2026-09-20T08:00:00Z"}
```

账号不存在、口令错误和禁用返回统一 401，避免把登录当账号枚举接口。认证依赖不可用返回 503，不能伪装成口令错误。

### 替换角色授权（API09）

```json
{"expectedVersion":4,"permissionCodes":["iam:user:read"],"dataScope":{"mode":"CUSTOM","departmentIds":["dept-a","dept-b"]}}
```

一次事务更新权限点及范围，成功返回新的 version。请求不能声明管理员身份；目标无效、引用失效或版本过期不会部分写入。

### 当前操作授权（API05）

```json
{"subjectId":"user-1","permission":"iam:user:read","scope":{"all":false,"departmentIds":["dept-a","dept-a1"],"selfUserId":"user-1"},"evaluatedAt":"2026-09-19T15:00:00Z"}
```

scope 表示部门集合 OR 本人；部门及下级在 IAM 判定时展开。ALL 为 true 时其他范围字段可为空，但只适用于响应中的 permission。未知/未授予操作不返回可放行上下文。响应不含 SQL、凭据摘要或所有角色的可变内部对象。

该响应只用于本次服务端判定，不是可重放的授权令牌。消费服务只接受自己向 IAM 请求的响应，不接受客户端转交的同形 JSON。

### 分页（API06）

```json
{"items":[{"id":"user-1","username":"example-user","departmentId":"dept-a","status":"ACTIVE","version":2}],"page":1,"size":20,"total":1}
```

### 错误

```json
{"code":"IAM_PERMISSION_DENIED","message":"无权执行该操作","traceId":"example-trace-id"}
```

| HTTP | 错误族 | 处理约定 |
|---|---|---|
| 400 | INVALID_ARGUMENT | 格式/枚举/分页等无效，不返回 SQL 和堆栈 |
| 401 | AUTHENTICATION_FAILED / SESSION_INVALID | 登录或会话失败；消费端重新认证 |
| 403 | PERMISSION_DENIED | 有效身份缺少操作权限，不建议重试 |
| 404 | RESOURCE_NOT_FOUND | 对详情/写入统一覆盖不存在和范围外资源 |
| 409 | VERSION_CONFLICT / INVARIANT_CONFLICT | 管理配置竞争、非法引用或保护规则；重新读取后决定 |
| 429 | TOO_MANY_REQUESTS | 登录等明确限流场景，附重试提示 |
| 500 | INTERNAL_ERROR | 缺少安全映射等程序错误对外隐藏详情、内部告警 |
| 503 | DEPENDENCY_UNAVAILABLE | IAM、会话或必需存储不可用，不允许失败放行 |

## 4. 公共组件的最小接口语义

| 契约语义 | 输入 → 输出 | 拥有者及限制 | 失败 |
|---|---|---|---|
| CurrentActor | 当前请求 → 已认证主体 ID | 服务端请求适配层；领域方法显式接收必要值，不读全局静态身份 | 缺少身份明确失败 |
| AuthorizationContextProvider | 当前凭据 + 服务端固定 PermissionCode → 当前操作授权上下文 | IAM 本地实现/消费方 HTTP 实现；不承诺支持任意用户代查 | 区分未登录、无权、不可用 |
| DataScope | all、部门集合、可选本人标识 | 只描述范围，不携带 SQL；资源映射由消费方拥有 | 无法安全映射拒绝 |
| 聚合仓储 | 有意义的聚合操作 → 领域对象或保存结果 | 由具体上下文定义；不向应用层暴露 MP Wrapper/IPage | 冲突与存储失败可区分 |

本阶段不规定 Java 方法签名和包名，不生成空接口。以上契约由实际切片证明调用方后实现；无需为了统一形式创建泛型万能 Repository。

## 5. 演进和待冻结事项

新增可选字段可以在同版本兼容演进；删除字段、改变授权语义、错误码含义、分页规则或默认放行行为属于破坏性变更。权限点编码登记后不可改名复用，停用需处理角色与导航引用。OpenAPI 与契约断言作为未来发布证据，不允许文档先声称存在尚未实现的端点。

H02 已接受当前协议约定、API 分组、会话政策及初始凭据交付方向。进入相关切片前仍需补齐字段级约束与验收映射，见[PLAN-02](../planning/project-plan.md)。未补齐的 endpoint 不拆成可执行编码任务，先完成对应设计任务。
