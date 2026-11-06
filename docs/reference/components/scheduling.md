# 定时任务

服务的定时任务统一经 `domain-forge-scheduling` 组件交给 PowerJob 调度：服务声明任务，组件在启动时登记，运维在 PowerJob 控制台修改周期、暂停或恢复、手动触发并查看执行记录，不需要重新发布应用。产品要求见[定时任务功能边界](../../product/01-产品定义.md#定时任务功能边界)。

决定记录：[ADR 0015](../../decisions/0015-定时任务的取舍.md)。

## 执行语义（实测）

| 情况 | 表现 |
|---|---|
| 周期触发 | 按 Cron（带秒）触发，分派到一个在线 worker |
| 上次未结束 | 不开始新的执行：触发被跳过，执行间隔随之拉长，从不并发；实例已创建却无法开始时，执行记录为失败 `too many instances` |
| 服务端停机期间 | 错过的触发不补跑，恢复后从下一个周期继续 |
| 没有在线 worker | 执行记录为失败 `no worker available` |
| 执行失败 | 记录为失败（结果为异常类型），不重试；计数 `domainforge.scheduling.run{result=failure}` 并记录错误日志 |
| 手动触发 | 控制台填写的参数作为 `TaskRun.parameters()` 传入，同样受单点执行约束 |

任务因此必须可以安全地重复执行（例如按条件删除、按水位处理），也不能依赖某一次触发一定发生。

## 组件

| 部分 | 行为 |
|---|---|
| 任务接口 | 服务实现 `ScheduledTask`（任务名、初始 Cron、说明、`run`）并声明为 Bean |
| 分派 | 所有任务共用一个 PowerJob 处理器（Bean `domainforgeTaskDispatcher`），按任务参数中的任务名分派；每次执行开启新链路的根 span `task <任务名>`，日志带其 traceId（见[链路追踪](tracing.md)） |
| 登记 | 启动完成后按任务名查找，不存在才创建，执行语义固定为单机执行、最多一个实例、实例与子任务都不重试、启用；已存在的任务不改动，控制台修改的周期与暂停状态保持有效。服务端不可达、应用未注册或密码不符时启动失败 |
| 启用条件 | `domainforge.scheduling.enabled=true` 且为 Web 应用：服务的维护命令（非 Web）不会启动 worker。部署环境（`config-center` profile）声明了任务却未启用时启动失败 |
| worker | 只用 HTTP 通信（排除 akka、mu）、内存存储、工作目录 `/tmp/powerjob-worker`（容器中的服务用户没有主目录） |
| 指标 | `domainforge.scheduling.run`（标签 `task`、`result`）、`domainforge.scheduling.duration` |

## 部署

服务、控制台与应用注册见[部署参考](../../../deploy/README.md#定时任务)。
