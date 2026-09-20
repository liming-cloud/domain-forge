# 架构决策记录

下列六项决定已随 DESIGN-v0.1 / H02 获得人工接受；备选方案不同时生效，运行兼容性仍以实测证据为准。状态只在各 ADR 维护，人工证据在[评审记录](../governance/review-register.md)登记。

| ADR | 决策主题 | 评审轮次 |
|---|---|---|
| [ADR-0001](0001-service-boundaries.md) | 统一 IAM 与部署边界 | R1 |
| [ADR-0002](0002-reuse-and-release.md) | 模板、组件与服务的复用和发布边界 | R1 |
| [ADR-0003](0003-ddd-isolation.md) | DDD 隔离粒度 | R2 |
| [ADR-0004](0004-authorization-consistency.md) | 鉴权链、会话撤销与授权一致性 | R2 |
| [ADR-0005](0005-runtime-and-compatibility.md) | Java / 构建 / Web 模型及兼容策略 | R2 |
| [ADR-0006](0006-data-scope-enforcement.md) | 数据范围的执行边界 | R2 |

Proposed → Accepted 需要用户明确确认；否决记为 Rejected；后续替换保留原记录并链接新 ADR，标为 Superseded。不为每个可逆参数创建 ADR。
