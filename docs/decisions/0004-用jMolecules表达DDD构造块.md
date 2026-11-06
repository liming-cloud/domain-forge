# 0004 用jMolecules表达DDD构造块

- 状态：已接受（2026-11-05）
- 关联 issue：[#93](https://github.com/liming-cloud/domain-forge/issues/93)、[#107](https://github.com/liming-cloud/domain-forge/issues/107)

## 背景

DDD 规则（聚合按 ID 引用、仓储只针对聚合根等）需要构建门禁检出。`shared` 中自定义的三个标记接口没有现成的检查规则。

## 决定

引入 jMolecules（新增依赖，已由人决定），优先使用它自带的 DDD 规则，自研规则只补缺。版本：`jmolecules-ddd` 1.10.0 与 `jmolecules-archunit` 0.33.0。

技术验证结果：与 ArchUnit 1.5.1、Java 21 兼容；jmolecules-ddd 用 1.10.0 和 2.0.1 各跑一次，都是正例 0 违规、两类反例都检出。`jmolecules-archunit` 1.6.0 是按 ArchUnit 0.23 编译的，与 1.x 二进制不兼容，并且会传递引入 spring-core 5.3，不得使用。

## 后果

由 [#107](https://github.com/liming-cloud/domain-forge/issues/107) 修订：

- [R-09](../rules/01-需求与领域建模.md#r-09-用-shared-中的三个角色标识)：角色标识由 `shared` 的标记接口改为 jMolecules 类型。
- [S-02](../rules/02-系统设计.md#s-02-领域层不依赖框架)：领域层允许依赖 `org.jmolecules.ddd`。
- [R-13](../rules/01-需求与领域建模.md#r-13-聚合要小按-id-引用聚合之外最终一致)、[R-14](../rules/01-需求与领域建模.md#r-14-仓储只针对聚合根) 的自动检查。

## 参考来源

- [jMolecules](https://github.com/xmolecules/jmolecules)、[jmolecules-integrations](https://github.com/xmolecules/jmolecules-integrations)
- Vaughn Vernon: [Effective Aggregate Design Part II](https://kalele.io/wp-content/uploads/2019/01/DDD_COMMUNITY_ESSAY_AGGREGATES_PART_2.pdf)
