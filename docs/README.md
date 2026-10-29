# 文档索引

| 类别 | 文档 | 内容 |
|---|---|---|
| 产品 | [产品定义](product/01-产品定义.md) | 目标、首版完整范围、边界与成功标准 |
| | [路线图](product/02-路线图.md) | 首版跟踪 issue、阶段计划、已知风险 |
| 架构 | [总体架构](architecture/01-总体架构.md) | 模块与依赖、DDD 分层、CQRS、事件、事务、缓存合同 |
| | [工程与构建](architecture/02-工程与构建.md) | 技术基线、Maven 结构、质量门禁、集成测试、CI |
| | [Web 组件](architecture/03-Web组件.md) | 响应外壳与错误转换的接入方式 |
| | [身份权限](architecture/04-身份权限.md) | IAM 模型、场景、会话与授权设计 |
| | [配置中心](architecture/05-配置中心.md) | Nacos 配置分层、动态项、启动与运行行为 |
| | [API 网关](architecture/06-API网关.md) | 网关路由、身份传递、防直连、限流与链路标识 |
| | [跨服务调用](architecture/07-跨服务调用.md) | Feign 组件合同、Same-Token 官方用法、服务专用接口 |
| | [消息与 Outbox](architecture/08-消息与Outbox.md) | 投递合同、顺序与去重、中继、RocketMQ 部署 |
| | [定时任务](architecture/09-定时任务.md) | PowerJob 调度、执行语义、任务登记与部署 |
| | [链路追踪](architecture/10-链路追踪.md) | traceId 贯穿网关、调用、消息与任务，采样与导出 |
| | [文件存储](architecture/11-文件存储.md) | 本地与 S3 存取合同、业务授权后转发、清理与备份恢复 |
| | [短信与验证码](architecture/12-短信与验证码.md) | 短信发送合同与失败分类、验证码签发校验与使用限制 |
| 规范 | [需求与领域建模](standards/01-需求与领域建模.md) | 从需求到场景、模型和上下文 |
| | [系统设计](standards/02-系统设计.md) | 模块、依赖、端口与用例 |
| | [技术与构建](standards/03-技术与构建.md) | 技术栈、依赖与漏洞 |
| | [源码质量](standards/04-源码质量.md) | 职责位置、格式、注释、复杂度 |
| | [持久化](standards/05-持久化.md) | SQL、迁移、事务、多数据源 |
| | [接口与错误](standards/06-接口与错误.md) | HTTP 接口、响应外壳、错误码 |
| | [安全与访问控制](standards/07-安全与访问控制.md) | 认证、授权、凭据、审计 |
| | [配置与运行](standards/08-配置与运行.md) | 配置来源、启动、运行 |
| | [故障与可观测性](standards/09-故障与可观测性.md) | 异常、日志、指标、健康检查 |
| | [测试与验证](standards/10-测试与验证.md) | 测试分层与证据 |
| 流程 | [开发流程](workflow/01-开发流程.md) | 分支、PR、评审清单、人工决定、文档维护 |
| 部署 | [部署参考](../deploy/README.md) | Compose 单机部署、初始化与验证 |
| 模板 | [服务模板使用说明](../platform/templates/ddd-service/01-使用说明.md) | 生成业务服务 |

AI 编码工具从 [AGENTS.md](../AGENTS.md) 开始。
