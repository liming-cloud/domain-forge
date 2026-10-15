# 最小真实工程与质量接入任务

## 目标与输入

来源：2026-10-15 本会话用户要求第五层验收收尾后进入第六层，“先明确最小真实工程与质量检查、CI 接入的设计、范围和验收标准，暂不开展业务实现”。第五层精确验收及收尾见[唯一记录](control-plane-verification.md#唯一任务记录)。[设计](../design/minimal-engineering.md)已获本轮实施确认；第五层验收不替代本单元工程验收。

服务于[产品目的](../../README.md#产品目的)与[持续研发路线](../../REBUILD.md#逐层产出)：以真实工程承接有效质量检查，后续能力按单元落实，不扩大为一次交齐所有平台功能。

## 当前范围与退出条件

2026-10-15 用户确认：“确认第六层首个单元的设计、范围及 E01—E12 验收标准，继续推进实施：先核查并固定版本与环境，再完成最小真实工程、质量检查和 CI 接入及验证，暂不扩展业务功能。”确认对象为提交 `146c154` 的设计正文。当前执行该单元并逐项取得真实证据；工程验收尚未获得。

设计批准、实际检查通过、候选提交和工程人工验收分别记录；正式验收前不写入 acceptance。

## 验收材料与剩余责任

首轮真实执行已覆盖构建、源码／架构、原生扫描、打包 JAR 运行及 17 项产品门禁测试（含拒绝反例）。最终交付须重新绑定提交对象；首轮结果不代替候选的完整核验。各项人工结论均待精确候选提交后确认。

真实 CI 曾在首次下载 NVD 全量历史资料时达到准备上限，记录为环境准备未完成；本轮已补齐准备日志保留，并允许以同配置原生数据库缓存作为输入。原生更新、当前工程扫描、必要断言及预算均保留，后续结果仍须绑定精确候选核验。

| 标准 | 核对材料及当前边界 |
|---|---|
| E01、E02 | `baseline.json`、官方 Wrapper、原生 Maven 观察与 `build` 结果；全新隔离缓存复验，固定 macOS／Linux 环境各自取得实际证据 |
| E03、E04 | `quality` 中实际编译源码、类型、职责和依赖绑定；反例分别拒绝未归属类型、缺说明、错误格式、复杂度和未声明 API |
| E05 | 原生 Surefire 必需测试和真实 HTTP 断言；跳过、零测试、错误断言、必需测试缺失和打包缺类／资源均拒绝 |
| E06 | 原生 HTML／JSON 报告、生产及测试 JAR 散列覆盖；关联依赖合并不漏扫，缺资料／损坏数据库和扫描失败不作零漏洞 |
| E07、E08 | 同次真实 JAR 的健康、端口替换、非法／占用端口和退出释放记录；未声明管理端点为 404，健康无组件细节 |
| E09、E10 | 本地及 CI 同用完整入口；产品作业的选择、缺失、跳过、失败、缺步骤反例；真实 SHA、run attempt 和 job／step 由 delivery 逐项核验 |
| E11 | `PG_PREPARATION`、`PG_EXECUTION_TIMEOUT` 与 `PG_NATIVE_FAILURE` 区分准备、执行上限和原生失败；下载失败不直接归因产品缺陷，也不报通过 |
| E12 | 普通语义评审、精确功能 SHA、真实 CI 及公开 delivery 结果提交本会话；人工验收尚未获得，`acceptance` 与 `closing` 保持空值 |

原生扫描首轮识别 `tomcat-embed-core:11.0.24` 的 11 条漏洞告警。保留原生标识、等级与报告，按[已确认原则](../standards/technology-and-build.md#已确认的依赖漏洞门禁原则)不自动阻断功能构建；这不是无风险或生产上线结论。未擅自覆盖 Boot 管理版本，后续结合实际使用场景评估并验证修复组合。当前只有回环技术健康服务，IAM、Nacos、数据库、业务 API、外部制品发布、独立消费、部署升级与性能验收仍未交付。

## 唯一任务记录

```control-plane-task
{
  "id": "minimal-engineering",
  "base": "146c154b50e31ecda7133169bbff1e1a3c5e78e8",
  "type": "create-project",
  "scopes": [
    "planning",
    "architecture",
    "build",
    "dependency-risk",
    "tests",
    "configuration",
    "runtime",
    "source-coverage",
    "http"
  ],
  "goals": [
    "PRODUCT#产品目的",
    "PRODUCT#产品成功标准"
  ],
  "unit": "ENGINEERING-TASK#目标与输入",
  "consumers": [
    "平台研发者",
    "执行研发任务的 Agent",
    "GitHub Actions"
  ],
  "design_refs": [
    "MINIMAL-ENGINEERING#范围与明确后续责任",
    "MINIMAL-ENGINEERING#工程组织与方案比较",
    "MINIMAL-ENGINEERING#版本与支持环境",
    "MINIMAL-ENGINEERING#最小运行合同",
    "MINIMAL-ENGINEERING#质量检查接入",
    "MINIMAL-ENGINEERING#使用路径",
    "MINIMAL-ENGINEERING#ci-接入与失败分类",
    "MINIMAL-ENGINEERING#验收标准",
    "MINIMAL-ENGINEERING#实施顺序与确认事项"
  ],
  "extra_refs": [
    "TASKS#已确认的验收后推进原则",
    "TASKS#已确认的-git-交付次序",
    "HTTP#已确认的-json-响应外壳",
    "BUILD#已确认的主技术栈",
    "BUILD#版本与兼容",
    "SOURCE#已确认的格式基线",
    "SOURCE#已确认的类型注释",
    "SOURCE#已确认的方法注释",
    "SOURCE#已确认的复杂度门禁",
    "SOURCE#已确认的测试复杂度基线"
  ],
  "verification": [
    {
      "check": "doc-integrity",
      "criterion": "设计、任务、活动和归档入口及来源一致，全部引用有效"
    },
    {
      "check": "control-plane-tests",
      "criterion": "当前任务切换、路由、状态和既有必要反例通过；不把任务登记当产品执行"
    },
    {
      "check": "semantic-review",
      "criterion": "逐项核对目标、单工程范围、真实检查接入、CI 对象和 12 项验收标准；技术证据不冒充人工验收"
    },
    {
      "check": "build-gate",
      "criterion": "依首个单元 E01—E12 对实际工程执行并核对证据，不借校准结果代证"
    },
    {
      "check": "dependency-risk-gate",
      "criterion": "依首个单元 E01—E12 对实际工程执行并核对证据，不借校准结果代证"
    },
    {
      "check": "behavior-scenarios",
      "criterion": "依首个单元 E01—E12 对实际工程执行并核对证据，不借校准结果代证"
    },
    {
      "check": "runtime-scenarios",
      "criterion": "依首个单元 E01—E12 对实际工程执行并核对证据，不借校准结果代证"
    },
    {
      "check": "product-scenarios",
      "criterion": "依首个单元 E01—E12 对实际工程执行并核对证据，不借校准结果代证"
    }
  ],
  "frontier": "2026-10-15 已按批准设计落地最小真实工程、五项产品检查和产品 CI／delivery 适配；当前候选交付按下表核对精确输入的本地及真实 CI 证据，工程人工验收仍待用户决定。",
  "pending": [
    "本单元完整验证及真实 Git／CI、delivery 结论以本会话提交的精确候选和本次报告为准，不沿用其他输入绿灯",
    "提交精确工程候选供人工验收；设计确认不等于工程验收"
  ],
  "deferred": [
    "IAM、Nacos、数据库和其余首版通用能力按后续单元实现",
    "外部制品发布、独立消费项目、部署升级及性能尚未执行"
  ],
  "acceptance": null,
  "closing": null
}
```
