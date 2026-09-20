# 文档入口与权威来源

当前产品设计已获人工接受，见 [DESIGN-v0.1 / H02](governance/review-register.md)。本轮新增的[文档控制面](governance/document-control-plane.md)与[项目执行规划](planning/project-plan.md)为待评审草案。导航不复制各文档的实时状态。

## 按问题读取

| 要回答的问题 | 唯一维护位置 | 类型 |
|---|---|---|
| 项目为何建设、包含什么、不包含什么 | [需求基线](design/00-baseline.md)、[产品范围](design/01-project-scope.md) | 产品要求与设计 |
| 整个项目分几步，现在推进什么 | [路线](roadmap.md)、[执行规划](planning/project-plan.md) | 规划；阶段在路线，近期任务状态在执行规划 |
| 服务归属、依赖方向和允许的访问方式 | [架构](design/02-architecture.md) | 规范设计 |
| IAM 术语、生命周期及授权规则 | [安全模型](design/03-iam-domain-security.md) | 规范设计 |
| 为什么作出重大取舍，何时重评 | [ADR 索引](adr/README.md) | 决策记录 |
| 技术依据、候选版本及验证方法 | [技术矩阵](design/04-technology-compatibility.md) | 设计与注明日期的事实 |
| 接口行为与验收预期 | [契约](design/05-api-contracts.md)、[验收矩阵](design/06-validation-matrix.md) | 规范设计；不是运行结果 |
| AI 如何执行、谁能评审合并发布 | [协作流程](governance/ai-development-workflow.md)、[AGENTS](../AGENTS.md) | 已有协作规则 |
| 文档放哪里、如何变更、归档与交付 | [文档控制面](governance/document-control-plane.md) | 治理方案草案 |
| 哪个版本获准、谁批准了什么 | [评审与授权记录](governance/review-register.md) | 人工决定登记 |
| 某次调查、变更和验证实际发生了什么 | `docs/records/`（按需创建）、Issue/PR/CI 证据 | 过程记录，不是规范 |
| 某个产品版本实际交付了什么 | `docs/releases/`（发行前创建），链接代码、使用文档和规则 | 固定版本交付清单 |

代码尚未产生；`docs/usage/`、`docs/records/`、`docs/releases/` 为规划目录，当前不创建空文件占位。根 README 是产品入口，本文是资料入口；二者均不另存一份规则正文。

## AI 的最小阅读顺序

1. [AGENTS](../AGENTS.md) 和[评审记录](governance/review-register.md)：当前授权、已接受版本与新提案边界。
2. [执行规划](planning/project-plan.md)：找到本次任务及其输入、依赖、输出和完成条件。
3. 该任务引用的规范设计、ADR、验收条目：仅加载相关内容。
4. 需要解释变化时再查关联过程记录；查阅已发布行为时使用发行清单固定的版本。

若状态与引用版本不一致，先核实对应批准记录及 Git 快照。聊天摘要、搜索结果、更新时间和文件名中的“final”都不构成规则生效依据。

## 本轮目录迁移

| 旧路径（DESIGN-v0.1 快照） | 当前路径 | 变化 |
|---|---|---|
| `docs/design/07-ai-development-workflow.md` | [协作流程](governance/ai-development-workflow.md) | 从技术设计移到协作治理；保留批准对象追溯 |
| `docs/design/08-review-register.md` | [评审记录](governance/review-register.md) | 从技术设计移到治理登记；人工记录只维护一份 |

旧版本通过 Git 提交访问，不复制历史文档树，也不保留可被误读成当前规范的旧文件。
