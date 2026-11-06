# 文档索引

AI 编码工具从 [AGENTS.md](../AGENTS.md) 开始，按任务类型路由到规则卡和参考页。

| 类别 | 文档 | 内容 |
|---|---|---|
| 规则 | [规则卡索引](rules/00-索引.md) | 卡片格式、检查标签、按原则与按主题的全部规则卡 |
| | [需求与领域建模](rules/01-需求与领域建模.md)、[系统设计](rules/02-系统设计.md)、[技术与构建](rules/03-技术与构建.md)、[源码质量](rules/04-源码质量.md) | 建模与结构、依赖与构建、代码风格 |
| | [持久化](rules/05-持久化.md)、[接口与错误](rules/06-接口与错误.md)、[安全与访问控制](rules/07-安全与访问控制.md)、[配置与运行](rules/08-配置与运行.md)、[故障与可观测性](rules/09-故障与可观测性.md)、[测试与验证](rules/10-测试与验证.md) | 各主题的规则卡 |
| 流程 | [开发流程](workflow/01-开发流程.md) | 任务与 issue、需求与设计、分支、PR、评审清单、人工决定、文档维护（W 卡） |
| 参考 | [工程与构建](reference/工程与构建.md) | 技术基线、Maven 结构、质量门禁、集成测试、CI、快速反馈命令 |
| | [错误码](reference/错误码.md) | 平台保留的 HTTP 状态与 `code` |
| | [验证索引](reference/验证索引.md) | 各能力由哪些测试与脚本证明 |
| | [身份权限](reference/身份权限.md) | IAM 接口、权限码、会话与授权合同、锁顺序 |
| | [观测数据](reference/观测数据.md) | 指标、日志、链路、健康的对接方式 |
| | [扩展路径](reference/扩展路径.md) | 各类能力的默认实现与经验证的扩展路径 |
| | [业务仓库起步清单](reference/业务仓库起步清单.md)、[平台升级同步](reference/平台升级同步.md) | 复制仓库后分开平台与业务资产、以 git 上游合并同步平台修复 |
| | 组件：[web-support](reference/components/web-support.md)、[persistence](reference/components/persistence.md)、[cache](reference/components/cache.md)、[access-control](reference/components/access-control.md)、[config](reference/components/config.md)、[service-call](reference/components/service-call.md)、[messaging](reference/components/messaging.md)、[scheduling](reference/components/scheduling.md)、[tracing](reference/components/tracing.md)、[file-storage](reference/components/file-storage.md)、[notification](reference/components/notification.md)、[idempotency](reference/components/idempotency.md)；服务：[gateway](reference/components/gateway.md) | 每个组件一页：合同、配置、用法 |
| 解释 | [总体架构](explanation/总体架构.md) | 仓库分区、组件依赖、DDD 分层、CQRS、事件、事务 |
| | [身份权限模型](explanation/身份权限模型.md) | IAM 边界、统一语言、场景、聚合与组织模型 |
| 决定 | [决定记录（ADR）](decisions/README.md) | 一个决定一个文件，含背景、决定与后果 |
| 平台 | `docs/platform/`（平台仓库专有，业务项目删除） | 产品定义、路线图、平台研发流程、质量模型与北极星、性能基线 |
| 部署 | [部署参考](../deploy/README.md) | Compose 单机部署、备份恢复、升级与各组件的部署信息（唯一来源） |
| 模板 | [服务模板使用说明](../platform/templates/ddd-service/01-使用说明.md) | 生成业务服务 |
