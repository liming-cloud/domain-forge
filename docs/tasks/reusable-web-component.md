# R2 首个可复用 Web 组件与独立消费任务

## 目标与输入

以两个自有工程真实消费版本化组件为本单元结果，先形成[设计、范围与验收标准](../design/reusable-web-component.md)，按已确认设计实施。R1 已获人工验收并完成必要收尾，唯一事实见[R1 记录](minimal-engineering.md#唯一任务记录)；当前 Git 起点为其记录提交 `dabc66c9e2d1d40bbe920a2d3f3fb360b582e32c`。

2026-10-16 本会话用户原文：“确认当前候选 6f6cea3 的 R1 人工验收通过，完成验收记录和必要收尾，允许提交推送；随后进入 R2，先明确首个可复用组件、平台 BOM 和两个独立消费项目验证的设计、范围及验收标准，暂不开展业务实现。”该来源为上一轮设计授权；本轮实施授权见下文。

## 当前范围与后续退出条件

组件、平台 BOM 与两个消费者的旧候选已完成真实 Git／CI 和 delivery-evidence 核对。阶段 1a 已修复 F01，并通过真实产品本地验证及语义复核；2026-10-19 已获本次候选提交推送与 Git／CI／delivery 核验授权。精确新候选与实际核验结果原位维护于人工验收清单，不沿用旧候选结果；产品人工验收尚未获得。最新[产品定位澄清](../product/rebuild.md#产品定位补充确认)已同步现有定义及路线，不扩展本单元实现范围。

## 本轮确认与执行边界

2026-10-16 本会话用户原文：“确认 R2-D01—D04 推荐方案及 R2-A01—A12 验收标准。继续推进，先落实确认记录并核查固定新增工具与环境版本，再完成 web-support、平台 BOM 和两个独立消费项目的实现及适用验证；暂不扩展业务功能或提交推送。”

确认对象为 R2-D01—D04 推荐方案和 R2-A01—A12 验收标准；确认前设计文件 SHA-256 为 `c91ead60674f5e9f10a64b756cf77463b03cbccba0bd346e82061ed55857e773`。这是设计与实施授权，不是产品验收通过。固定输入、环境和实际证据保存于 `.run-artifacts/r2-implementation/`。

验证顺序：版本与环境 → 组件及消费 POM → 隔离 A／B 构建及真实 HTTP → 适用源码／架构／依赖／失败反例 → 固定输入复验及语义评审。缺陷先复现，不为本次新实现声明未发生的完整 TDD 过程。

## 阶段一修复授权与诊断边界

2026-10-19 用户确认修订后的整体方案、六阶段顺序和 A01—A12；来源与固定对象见[总体确认](../product/rebuild.md#整体方案与阶段一确认)。该次授权仅在现有 R2-01 上完成 F01 复现、修复、适用本地验证与语义评审，并更新原人工验收清单，不含提交推送。上述本地工作已完成；本次候选交付新增授权见下节，不由 2026-10-16 的历史授权推定。

未知故障及拒绝扩展失败必须保留可定位的内部诊断，同时保持原安全 HTTP 合同。新增测试先在未修复实现上实际失败，再验证修复；真实消费者还须核对本次请求产生的日志、原因类型与代码位置，不能只凭单元日志捕获或旧候选 CI。执行材料统一位于 `.run-artifacts/f01-local/`，实际结果及缺口按下方当前记录维护。

## 候选交付授权与证据定位

2026-10-19，本会话用户原文：“继续推进，允许提交推送当前已验证的 F01 修复候选，补齐真实 Git／CI 和 delivery-evidence 核验并更新人工验收清单；原生漏洞告警如实保留，暂不写入验收通过、不进入阶段 1b 或修改 CI。”

该授权覆盖本仓库 `main` 上当前已验证 F01 修复及同步文档的普通提交推送、真实 CI 与 delivery-evidence 核对。授权前 201 项维护输入与本地验证收尾摘要 `a186eba5d955027e365447e932e0cf45809861f79abda114102494ad48c4ade4` 一致；仅补充本次授权、状态及派生索引，产品实现、检查实现、公开合同和 CI 不变。本次材料位于 `.run-artifacts/f01-delivery/`，不提交临时证据目录。

精确功能 SHA、CI run／attempt、原生报告比较和公开 delivery 结果原位维护于 `.run-artifacts/r2-delivery/acceptance-checklist.md`；该清单汇总技术证据，不是另一份任务或人工验收状态。提交前不预记远端通过；提交后不为回填 CI 进度追加提交。新候选尚未取得人工验收，acceptance／closing 保持为空，不进入阶段 1b、不修改 CI。

历史候选 `00aa115404c3ed39bb2475579b4ea514ad10b7a4` 的真实 [CI 38874575955](https://github.com/liming-cloud/domain-forge/actions/runs/38874575955) 曾有 17 个 job 成功，delivery 为 `candidate-checked`，人工验收与完整交付均为 false。随后真实 JAR 复现证明 `GET /unknown` 缺少内部原因诊断，见 `.run-artifacts/r2-delivery/unknown-failure-reproduction.json`。当前 F01 已补红绿及实际日志证据，但这些旧 Git／CI 结果不覆盖新修复。

## 唯一任务记录

```control-plane-task
{
  "id": "reusable-web-component",
  "base": "dabc66c9e2d1d40bbe920a2d3f3fb360b582e32c",
  "type": "platform-extension",
  "scopes": [
    "planning",
    "architecture",
    "component",
    "consumption",
    "build",
    "http",
    "contract",
    "extension",
    "java-source",
    "java-types",
    "java-methods",
    "tests",
    "dependency-risk",
    "runtime",
    "failure",
    "observability"
  ],
  "goals": [
    "PRODUCT#产品目的",
    "PRODUCT#产品成功标准"
  ],
  "unit": "REUSABLE-WEB-TASK#目标与输入",
  "consumers": [
    "平台研发者",
    "后续 IAM 与业务项目的 HTTP 入口维护者",
    "独立消费项目维护者"
  ],
  "design_refs": [
    "DEVELOPMENT-STRUCTURE#ddd-边界与代码映射",
    "DEVELOPMENT-STRUCTURE#产品交付单元与依赖",
    "DEVELOPMENT-STRUCTURE#模板与版本消费方案",
    "DEVELOPMENT-STRUCTURE#研发顺序与当前迁移",
    "REUSABLE-WEB#状态与目标",
    "REUSABLE-WEB#首个组件与范围",
    "REUSABLE-WEB#公开合同与职责",
    "REUSABLE-WEB#maven-组织与平台-bom",
    "REUSABLE-WEB#两个独立消费项目与使用路径",
    "REUSABLE-WEB#质量检查与-ci-接入设计",
    "REUSABLE-WEB#r2-01-验收标准",
    "REUSABLE-WEB#实施顺序与待确认决定",
    "REUSABLE-WEB#一手资料与证明范围"
  ],
  "extra_refs": [
    "REBUILD#产品定位补充确认",
    "PRODUCT#身份权限交付形态",
    "TASKS#已确认的验收后推进原则",
    "TASKS#责任与人工决定",
    "BUILD#制品与独立消费",
    "HTTP#已确认的-json-响应外壳",
    "HTTP#已确认的接口合同来源",
    "HTTP#消费处理与观测",
    "CONFIG#默认约定与必要输入",
    "VERIFY#环境与真实证据",
    "VERIFY#门禁与失败反例",
    "SOURCE#已确认的类型注释",
    "SOURCE#已确认的方法注释",
    "REBUILD#整体方案与阶段一确认"
  ],
  "verification": [
    {
      "check": "doc-integrity",
      "criterion": "确认来源、实现范围、登记与索引一致，引用有效；未执行项不记通过"
    },
    {
      "check": "control-plane-tests",
      "criterion": "任务切换、已验收归档、待确认范围及文档入口的既有有效反例通过"
    },
    {
      "check": "semantic-review",
      "criterion": "依据现行产品／DDD／HTTP／消费规范审阅 R2-D01—D04 和 R2-A01—A12；不把组件验证应用视为业务实现，不把计划当结果"
    },
    {
      "check": "build-gate",
      "criterion": "R1、组件及两个独立消费构建，实际测试、源码／架构、普通 JAR、生成 POM、固定版本与来源检查"
    },
    {
      "check": "dependency-risk-gate",
      "criterion": "组件、A、B 及 R1 原生扫描覆盖实际生产／测试依赖，保留告警"
    },
    {
      "check": "behavior-scenarios",
      "criterion": "两项目实际 HTTP 与独立预期、真实扩展及固定首版合同一致"
    },
    {
      "check": "runtime-scenarios",
      "criterion": "实际 JAR 散列绑定、独立运行与停机、健康边界及 R1 原断言"
    },
    {
      "check": "product-scenarios",
      "criterion": "完整产品入口、缺制品／错版本／跳过测试、输入及合同漂移反例"
    },
    {
      "check": "observation-tests",
      "criterion": "现行原生观测校准的真实接收、分类、故障隔离、敏感内容和恢复反例；不据此宣称 Web 组件已接入遥测平台"
    }
  ],
  "frontier": "阶段 1a 的 F01 已完成先红后绿、真实 A/B 消费本地验证及语义复核。2026-10-19 已获当前候选提交推送和 Git／CI／delivery 核验授权；精确对象及实际结果只维护于 .run-artifacts/r2-delivery/acceptance-checklist.md，不能从本段预推已通过。原生漏洞告警保留，R2-01 人工验收未获得，不进入阶段 1b 或修改 CI。",
  "pending": [
    "对清单所列精确候选及 R2-01 范围取得用户独立人工验收；总体设计批准、本地和 CI 通过均不代替验收",
    "原生漏洞告警继续保留，生产风险处置未完成；本次不作风险豁免",
    "阶段 1b 及后续能力须满足前序验收及对应执行授权；当前不迁移或修改 CI"
  ],
  "deferred": [
    "阶段 1b：结论文档、产品/工具路径与薄 dev 入口分批迁移；当前不迁移、不修改 CI",
    "阶段 2：先接纳新结构规则，再完成实际所需持久化/事务、配置、必要观测和最小独立模板；不预建空壳能力",
    "阶段 3—6：IAM 首个真实切片、完整 IAM 与微服务交付、首版组件补齐、企业组合验收；未知详细设计在对应单元确认",
    "Q0—Q4 检查优化独立推进，未经执行授权不切换现行政策；性能、真实升级、部署恢复和 AI 任务验收仍保留",
    "Tomcat 原生漏洞告警保留；未作风险豁免、版本变更或生产安全放行"
  ],
  "acceptance": null,
  "closing": null
}
```

## 本轮验证清单

当前阶段 1a 固定输入的本地产品报告为 `.run-artifacts/f01-local/product-full.json`；精确输入、环境、适用检查与文档收尾差异以同目录 `verification-summary.json` 为准，语义结论见 `semantic-review.json`。人工验收逐项清单原位更新于 `.run-artifacts/r2-delivery/acceptance-checklist.md`。本节只维护当前结论，旧 `r2-implementation`／`r2-delivery` 报告保留其历史对象，不代表新修复已获独立检查或人工批准。

F01 已有实际失败用例（6 项新增测试中 4 项因缺诊断失败，无运行错误），修复后组件 10 项、R1 1 项、A 1 项、B 6 项 JUnit 通过。37 个打包 HTTP 场景验证原合同；A 的未知异常产生 1 条、B 的未知异常及三类扩展失败产生 4 条可定位且不含测试秘密的诊断。46 项产品门禁反例通过，包含新增诊断缺失／泛化／重复／无位置／泄漏拒绝；没有放宽原断言。补充校准及文档结果由上述汇总记录，未执行部分不记通过。

| 验收项 | 当前本地技术结论 | 后续责任 |
|---|---|---|
| R2-A01—A03 | 普通 JAR、公开边界与 A/B 默认接入通过；成功、空列表、null、拒绝和受理语义保持 | 精确候选人工核对；不外推真实业务完成 |
| R2-A04—A05 | 安全 HTTP 失败与内部可定位脱敏诊断同时通过；确切类型扩展、抛错／非法／null 回退保持 | F01 本地已修复；真实身份链与完整观测接入不在本单元 |
| R2-A06—A08 | 未接入协议、已提交响应、BOM/自有 parent、独立仓库制品及 A/B 运行隔离通过 | 未发布公共仓库，不等同生产部署 |
| R2-A09—A10 | 原失败反例与新增诊断反例通过；公开合同未改，manifest 仅重新绑定实际工程输入 | 无历史组件版本，不声称升级兼容 |
| R2-A11 | 当前产品本地验证通过；补充校准与文档检查以精确汇总为准 | 本次已获独立核验授权，实际结果见精确候选清单，不将旧通过覆盖新对象 |
| R2-A12 | 主 Agent 内联语义复核未发现当前 F01 范围剩余阻断；未宣称独立多人评审 | **精确候选人工验收仍待确认**，acceptance／closing 保持空 |
| 整体方案 A05 | 本轮 F01 复现、修复和本地语义证据已具备 | 独立候选核验按本次授权执行并以清单实际结果为准；R2-01 人工验收仍保留，不标记整个 A05 通过 |

当前原生扫描覆盖组件 22、A/B 各 74、R1 56 个依赖散列。组件未报告漏洞；A/B 及 R1 的 Tomcat 11.0.24 各保留 11 项告警（4 critical、5 high、2 medium）。未抑制告警或作生产放行结论。
