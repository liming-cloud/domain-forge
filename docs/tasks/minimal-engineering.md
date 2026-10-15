# 最小真实工程与质量接入设计任务

## 目标与输入

来源：2026-10-15 本会话用户要求第五层验收收尾后进入第六层，“先明确最小真实工程与质量检查、CI 接入的设计、范围和验收标准，暂不开展业务实现”。第五层精确验收及收尾见[唯一记录](control-plane-verification.md#唯一任务记录)。本任务产物是[设计提案](../design/minimal-engineering.md)，尚未获得设计实施确认；第五层验收不替代本单元工程验收。

服务于[产品目的](../../README.md#产品目的)与[持续研发路线](../../REBUILD.md#逐层产出)：以真实工程承接有效质量检查，后续能力按单元落实，不扩大为一次交齐所有平台功能。

## 当前范围与退出条件

本轮只交付必要设计、使用路径、范围、失败分类和 E01—E12 验收标准，并完成文档及普通语义评审。保留版本核查、实际实现和真实产品验证的未执行事实。推荐方案及待确认事项见[设计确认事项](../design/minimal-engineering.md#实施顺序与确认事项)；设计确认前不创建产品工程或业务实现。

CI 对本次文档和测试夹具调整的成功只能证明相应输入检查，不证明拟建产品入口存在或方案已获人工批准。

## 唯一任务记录

```control-plane-task
{
  "id": "minimal-engineering",
  "base": "ddd9e9829746d8591b861cfbe329f4ce11dcb618",
  "type": "documentation",
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
      "criterion": "逐项核对目标、单工程范围、真实检查接入、CI 对象和 12 项验收标准；设计提案不冒充实施批准"
    }
  ],
  "frontier": "第五层已验收并收尾；本任务仅形成首个真实工程单元的设计提案、范围和 12 项验收标准。源码与真实产品 CI 尚未实现；当前等待设计范围确认，不开展业务实现。",
  "pending": [
    "审阅并确认最小单工程、运行合同、质量检查和 CI 接入范围及 E01—E12 验收标准",
    "设计确认后，实施前核查并固定具体 JDK／Maven／Boot／插件组合及环境，再按该单元依赖顺序实施；当前不写业务代码"
  ],
  "deferred": [
    "本单元真实工程、Wrapper、源码、构建、扫描、运行和 CI 接入尚未实现，五项产品结果检查未执行",
    "IAM、Nacos、数据库和其余首版通用能力按后续适用单元设计、实现、验证",
    "外部制品发布、两个独立消费项目、部署、升级和性能场景均未执行；产品首版承诺保持不变"
  ],
  "acceptance": null,
  "closing": null
}
```
