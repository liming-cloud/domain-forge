# 消息与 Outbox

服务之间的异步集成事件统一经 `domain-forge-messaging` 组件：发布用同事务 Outbox＋中继，消费用本地事务内的去重，传输为 RocketMQ 5。产品要求见[异步消息功能边界](../../product/01-产品定义.md#异步消息功能边界)，领域事件与集成事件的区分见[总体架构](../../explanation/总体架构.md#领域事件与集成事件)，规则卡见[S-23](../../rules/02-系统设计.md#s-23-集成事件经同事务-outbox-发布)。

决定记录：[ADR 0014](../../decisions/0014-消息与Outbox的取舍.md)。

## 投递合同

| 方面 | 合同 |
|---|---|
| 发布 | `EventPublisher.publish` 只能在业务事务内调用：事件与业务数据一起提交，回滚时一并消失；没有事务时抛出异常 |
| 发送 | 提交后由中继发送；发送失败按 2 的幂退避（上限 5 分钟），记录永不丢弃。业务数据提交后、发送前应用中断，重启后（或其他实例）继续发送 |
| 重复 | 至少一次：中继可能在发送成功、记录结果之前中断，同一事件（同一事件 ID）会再次发送 |
| 去重 | 消费方在本地事务内先登记“消费组＋事件 ID”，再执行处理者；重复投递只确认不重复生效。去重记录保留 7 天，超过保留期的重复不再识别 |
| 顺序 | 普通主题不保证顺序（事件可带聚合版本，由消费方丢弃过期事件）。FIFO 主题：同一顺序键的事件按 Outbox 顺序逐条发送（前一条未发成功不发后一条），消费组为顺序消费组，前一条处理失败时同键后续消息等待 |
| 失败 | 处理者抛出异常则回滚，消息稍后重投；共投递 5 次仍失败时计数 `domainforge.messaging.dead-letter` 并记录错误日志，服务端转入 `%DLQ%<消费组>` |
| 不在合同内 | 全链路恰好一次；跨主题或全局顺序；处理者中的不可回滚外部副作用（需另行设计幂等键或状态核对） |

事件信封（消息属性）：`df-event-id`、`df-event-type`、`df-event-version`（合同版本，不是聚合版本）、`df-event-key`（业务键）；消息体是发布方选择的 JSON。主题上可能出现多种事件类型，消费方忽略不认识的类型；认识的类型遇到不认识的合同版本时抛出异常，让消息进入死信并告警，而不是静默跳过。

## 组件

| 部分 | 行为 |
|---|---|
| Outbox 表 `domainforge_outbox` | 服务在自己的迁移中创建（结构见组件 `domain-forge/messaging/schema.sql`）；`domainforge.messaging.outbox.enabled=true` 启用发布。列 `trace_parent` 记录发布时的链路，用于中继与消费方延续同一链路（见[链路追踪](tracing.md)）；在加入该列之前已建表的服务，须新增迁移 `alter table domainforge_outbox add column trace_parent varchar(64)`，不能修改已执行的迁移 |
| 中继 | 每秒扫描，事务提交后立即唤醒；`FOR UPDATE SKIP LOCKED` 抢占，多实例分担；有顺序键的只取该键最早一条。一批中出现发送失败即停止本批，不在持有行锁的事务里逐条等超时。已发送记录保留 7 天后清理 |
| 消费 | 每个 `EventHandler` Bean 对应一个推送消费者（消费组、主题由处理者声明）；去重表 `domainforge_inbox` 由服务在迁移中创建 |
| 连接 | `domainforge.messaging.endpoints`（Proxy 地址）；需要连接（启用中继或有处理者）却未配置时启动失败 |
| 指标 | `domainforge.messaging.outbox.pending`（积压）、`.outbox.sent`、`.outbox.failed`、`domainforge.messaging.duplicate`、`domainforge.messaging.dead-letter`（按消费组） |
| 测试开关 | `domainforge.messaging.relay.enabled=false` 只写 Outbox 不发送；`domainforge.messaging.consumer.enabled=false` 只装配处理者不连接。只用于没有 RocketMQ 的测试 |

## 服务中的写法

```
application/contract/event/<事件>     公开合同：字段、主题、类型、合同版本
application/port/event/<端口>          发布端口：应用层不依赖 JSON 或消息组件以外的技术
infrastructure/messaging/<实现>        序列化并调用 EventPublisher；消费方实现 EventHandler，把对方合同翻译为本服务命令
```

- 发布方拥有事件的公开含义；消费方用自己的翻译，不共享发布方的类。
- 处理者只做本地事务内的写入；它被调用时去重记录已在同一事务中，处理失败会一起回滚。
- 新增主题或消费组时在 `deploy/rocketmq/topics.conf` 登记；主题类型（NORMAL／FIFO）创建后不改，需要改变顺序语义时新建主题。

## 部署

服务、主题登记、存储卷与恢复前提见[部署参考](../../../deploy/README.md#消息)。
