# Domain Forge

面向后续项目复用、以 AI 研发和人工评审为主要协作方式的 DDD 微服务脚手架。

**当前阶段：设计草案 v0.1，尚未形成经人工确认的研发基线。** 当前交付物是设计文档；不包含应用源码、构建工程或运行环境。编写文档的授权不等于启动研发。

## 从这里开始

1. 阅读[项目定位与范围](docs/design/01-project-scope.md)，了解使用者获得什么、还需自行开发什么。
2. 阅读[总体架构](docs/design/02-architecture.md)，先评审服务职责和复用方式。
3. 在[评审记录](docs/design/08-review-register.md)中处理当前轮次的问题；当前为 R1 方向评审。
4. 核心设计确认后，再评审[研发路线](docs/roadmap.md)中的任务切片与启动条件。

## 文档导航

| 文档 | 唯一维护的内容 |
|---|---|
| [需求基线与状态](docs/design/00-baseline.md) | 用户已确认约束、现场事实、状态定义、证据边界 |
| [项目定位与范围](docs/design/01-project-scope.md) | 使用场景、产品边界、复用和升级职责 |
| [总体架构](docs/design/02-architecture.md) | 上下文、服务和分层职责、依赖、运行拓扑 |
| [IAM 领域与安全](docs/design/03-iam-domain-security.md) | 术语、模型、授权公式、生命周期、信任边界 |
| [技术选型与兼容矩阵](docs/design/04-technology-compatibility.md) | 官方资料、版本候选、能力边界、待实测项目 |
| [API 与公共契约](docs/design/05-api-contracts.md) | API 分组、协议示例、公共接口、错误及演进规则 |
| [验证与验收矩阵](docs/design/06-validation-matrix.md) | 未来测试场景、预期、验证层级和追溯关系 |
| [AI 研发与评审流程](docs/design/07-ai-development-workflow.md) | 任务模板、PR 模板、CI 设计、人工职责与发布流程 |
| [评审记录](docs/design/08-review-register.md) | 三轮评审、待决问题、人工决定和基线状态 |
| [ADR 索引](docs/adr/README.md) | 候选方案、取舍、后果和重评条件 |
| [研发路线](docs/roadmap.md) | 设计交付进度和未来研发切片 |

仓库内的 AI 工作规则见 [AGENTS.md](AGENTS.md)。所有运行时能力及未来验收用例均尚未执行；文档一致性检查不能替代兼容性测试或人工设计评审。
