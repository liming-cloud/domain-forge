# leave-service

请假审批服务：员工提交年假申请，部门审批人审批，年假余额在通过时扣减。由 `platform/templates/ddd-service` 生成（不带示例），生成后归本服务维护。研发前先读仓库根目录的 `AGENTS.md` 与 [模板使用说明](../../templates/ddd-service/01-使用说明.md)；模型与取舍见[业务-0001 年假账户聚合](../../../docs/decisions/业务-0001-年假账户聚合.md)。

## 业务规则

| 规则 | 所有者 | 失败结果 |
|---|---|---|
| 状态机：草稿 → 待审 → 通过／驳回；草稿与待审可撤回；通过、驳回、撤回是终态 | `LeaveLedger`／`LeaveRequest` | 409 `LEAVE_STATE_CONFLICT` |
| 同一人占用时段的申请（草稿、待审、已通过）互不重叠，起止日期都包含在内 | `LeaveLedger` | 422 `LEAVE_PERIOD_OVERLAP` |
| 通过时按自然日扣减余额，余额不能为负 | `LeaveLedger` | 422 `LEAVE_BALANCE_INSUFFICIENT` |
| 状态命令携带的申请版本须与当前一致 | `ApplyLeave`／`ApproveLeave` | 409 `VERSION_CONFLICT` |
| 同一人的并发修改只有一个成功（账户版本条件更新） | `SqlLeaveLedgers` | 409 `VERSION_CONFLICT` |
| 只能挂靠申请权限范围内的部门 | `ApplyLeave` | 403 |
| 只能提交、撤回本人的申请 | `ApplyLeave` | 404 `LEAVE_REQUEST_NOT_FOUND` |
| 只能审批审批权限部门范围内的申请 | `ApproveLeave` | 404 `LEAVE_REQUEST_NOT_FOUND` |
| 不能通过或驳回本人的申请（不论范围） | `ApproveLeave` | 403 |
| 起止日期必填、结束不早于开始、单次最多 366 天；发放 1 至 366 天 | `LeavePeriod`／`LeaveLedger` | 400 `LEAVE_INVALID` |

## 接口

ID 以十进制字符串传输，日期为 ISO 格式（`2026-12-01`）；失败为 Problem Details。每份申请有版本（起草时为 1，每次状态迁移加一），列表与命令结果都返回 `version`；提交、撤回、通过、驳回的请求体为 `{"expectedVersion":n}`。

| 接口 | 访问策略 | 说明 |
|---|---|---|
| `POST /api/leave-requests` | `leave:request:apply` | 起草：`{"unitId":"…","startDate":"…","endDate":"…"}`，返回 `{"id","version","status"}` |
| `POST /api/leave-requests/{id}/submit`、`/withdraw` | `leave:request:apply` | 本人提交、撤回 |
| `POST /api/leave-requests/{id}/approve`、`/reject` | `leave:request:approve` | 审批通过（扣减余额）、驳回；不能处理本人的申请 |
| `GET /api/me/leave-requests?after=&limit=` | 登录 | 我的申请（纯查询，按 ID 键分页，limit 1 至 100） |
| `GET /api/leave-requests/pending?after=&limit=` | `leave:request:approve` | 待我审批：审批范围内部门的待审申请 |
| `GET /api/me/leave-balance` | 登录 | 我的余额 `{"accountId","remainingDays"}` |
| `POST /api/leave-balances/{accountId}/grants` | `leave:balance:grant`（须 ALL 范围） | 发放天数 `{"days":10}`，返回发放后的余额 |

## 权限与角色

权限目录在 `src/main/resources/iam/permissions.json`（命名空间 `leave`），由系统管理员安装到 IAM（`PUT /api/permission-namespaces/leave`），再建角色授予。建议的角色：

| 角色 | 授予 |
|---|---|
| 员工 | `leave:request:apply`，范围 DEPARTMENT（可挂靠本人所在部门；SELF 不够，起草须挂靠部门） |
| 部门审批人 | `leave:request:approve`，范围 DEPARTMENT 或 DEPARTMENT_AND_DESCENDANTS |
| 人事 | `leave:balance:grant`，范围 ALL |

## 运行

| 环境变量 | 含义 |
|---|---|
| `LEAVE_SERVICE_DB_URL`、`LEAVE_SERVICE_DB_USER`、`LEAVE_SERVICE_DB_PASSWORD` | 本服务自己的 PostgreSQL 库，启动时执行 Flyway 迁移 |
| `LEAVE_SERVICE_REDIS_HOST`、`LEAVE_SERVICE_REDIS_PORT`、`LEAVE_SERVICE_REDIS_PASSWORD` | 与 IAM 共用的会话 Redis |
| `LEAVE_SERVICE_IAM_URL` | 直连 IAM 的地址；为空时经 Nacos 发现名为 `iam` 的服务 |
| `LEAVE_SERVICE_DISCOVERY_ENABLED`、`LEAVE_SERVICE_NACOS_SERVER_ADDR` | 是否注册到 Nacos 及其地址 |
| `LEAVE_SERVICE_PORT`、`LEAVE_SERVICE_MANAGEMENT_PORT` | 业务端口（默认 8090）与回环管理端口（默认 9092） |

部署：覆盖文件 `deploy/services/leave-service.compose.yml`，配置种子 `deploy/nacos/config/leave-service.yaml`，网关路由在 `deploy/nacos/config/gateway.yaml` 的 `leave-service` 一项；步骤见[部署参考](../../../deploy/README.md#接入业务服务)。

## 验证

```bash
platform/mvnw -f platform/pom.xml verify -pl services/leave-service -am
```

`LeaveLedgerTest` 覆盖每个合法与非法迁移、时段重叠、余额扣减与不足；`LeaveCommandsTest` 用内存仓储覆盖授权、禁止自批、范围外按不存在处理与申请版本、账户版本冲突；`LeaveServiceIT` 在真实 PostgreSQL 与会话 Redis 上验证两条主链路、越权审批、禁止自批、申请版本不符、并发通过不超扣、并发起草不重叠与 IAM 故障。
