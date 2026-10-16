# R2 首个可复用 Web 组件与独立消费任务

## 目标与输入

以两个自有工程真实消费版本化组件为本单元结果，先形成[设计、范围与验收标准](../design/reusable-web-component.md)，按已确认设计实施。R1 已获人工验收并完成必要收尾，唯一事实见[R1 记录](minimal-engineering.md#唯一任务记录)；当前 Git 起点为其记录提交 `dabc66c9e2d1d40bbe920a2d3f3fb360b582e32c`。

2026-10-16 本会话用户原文：“确认当前候选 6f6cea3 的 R1 人工验收通过，完成验收记录和必要收尾，允许提交推送；随后进入 R2，先明确首个可复用组件、平台 BOM 和两个独立消费项目验证的设计、范围及验收标准，暂不开展业务实现。”该来源为上一轮设计授权；本轮实施授权见下文。

## 当前范围与后续退出条件

组件、平台 BOM 与两个消费者的实现和适用本地验证已完成。本轮按下述新授权固定并提交推送候选，核对真实 Git／CI 和 delivery-evidence，更新人工验收清单；产品人工验收尚未获得。

## 本轮确认与执行边界

2026-10-16 本会话用户原文：“确认 R2-D01—D04 推荐方案及 R2-A01—A12 验收标准。继续推进，先落实确认记录并核查固定新增工具与环境版本，再完成 web-support、平台 BOM 和两个独立消费项目的实现及适用验证；暂不扩展业务功能或提交推送。”

确认对象为 R2-D01—D04 推荐方案和 R2-A01—A12 验收标准；确认前设计文件 SHA-256 为 `c91ead60674f5e9f10a64b756cf77463b03cbccba0bd346e82061ed55857e773`。这是设计与实施授权，不是产品验收通过。固定输入、环境和实际证据保存于 `.run-artifacts/r2-implementation/`。

验证顺序：版本与环境 → 组件及消费 POM → 隔离 A／B 构建及真实 HTTP → 适用源码／架构／依赖／失败反例 → 固定输入复验及语义评审。缺陷先复现，不为本次新实现声明未发生的完整 TDD 过程。

## 候选交付授权与证据定位

2026-10-16 本会话用户原文：“继续推进，允许提交推送当前已验证的 R2-01 候选，补齐真实 Git／CI 和 delivery-evidence 核验，更新人工验收清单；原生漏洞告警如实保留，暂不写入验收通过、不进入后续单元或扩展业务功能。”

该授权覆盖本仓库 `main` 上的 R2-01 普通候选提交推送和真实 CI 核验，不授予人工验收或后续单元准入。本轮起点与上一轮本地完整报告的 200 项维护输入逐项一致；之后仅同步当前授权、入口说明和派生索引，不修改已验证实现、合同或检查断言。

精确功能 SHA、CI run／attempt、实际报告与公开 delivery 结果记录于 `.run-artifacts/r2-delivery/acceptance-checklist.md` 及同目录材料。清单只汇总 Agent 技术结论和待确认事项，不是另一份任务或人工验收状态。提交前不预记远端通过；提交后不为回填最新 CI 结果追加进度提交。

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
    "runtime"
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
    "SOURCE#已确认的方法注释"
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
    }
  ],
  "frontier": "R2-01 组件、BOM 和两个独立消费工程已落地，适用定向验证已完成；固定输入整体验证与语义评审的本轮结果见验证清单及 .run-artifacts/r2-implementation/verification-summary.md。已获普通提交推送授权，真实 Git／CI 与 delivery 由 .run-artifacts/r2-delivery/acceptance-checklist.md 绑定精确候选；未获产品人工验收。",
  "pending": [
    "按本轮授权固定 Git 候选并核对真实 CI 与 delivery；结果由精确候选清单定位，不预记自身提交与 CI 结果",
    "取得精确候选的 R2-01 产品人工验收；设计确认不代替实现验收"
  ],
  "deferred": [
    "R2 后续：Nacos 配置、最小应用观测、IAM 所需单库迁移／持久化及模板雏形，仍须各自设计、实现和验收",
    "R3 真实 IAM、后续网关及其余首版能力；R2 响应工厂不代替鉴权",
    "首个组件历史版本不存在，真正跨版本升级留待下一实际版本；公共制品发布、部署升级恢复与性能仍未完成",
    "继承 R1 原生漏洞告警，后续实际组合变更时由平台维护者评估；无生产放行结论"
  ],
  "acceptance": null,
  "closing": null
}
```

## 本轮验证清单

确认记录与工具来源已落实，实现范围保持 R2-01。最终本地报告为 `.run-artifacts/r2-implementation/product-final.json`，明细、精确输入、复验与告警汇总为 `.run-artifacts/r2-implementation/verification-summary.md`；以实际 `ok`、散列与诊断为准。本表列证明责任，不预写尚未完成的 Git／CI 或人工验收。

| 验收项 | 本轮实现与本地证据 | 后续责任 |
|---|---|---|
| A01 | 普通组件 JAR、独立消费 POM、仅两个真实平台坐标；原生构建、源码及架构观察 | 人工核对公开责任 |
| A02 | A 无 parent；默认装配、显式 Controller 标记及实际打包 HTTP | 精确候选验收 |
| A03 | A／B 各自客户端测试；成功、空列表、null、200 拒绝、202 受理的独立预期 | 不外推为真实业务完成 |
| A04 | 400／401／403／503／500 保留状态；独立固定安全提示与非 sentinel 泄漏反例 | 真实身份判断属于 R3 |
| A05 | B 公开确切类型映射；非法结果、抛错及未知异常安全失败；重复／宽泛声明真实启动拒绝 | 不引入领域异常基类 |
| A06 | 未接入入口、二进制、流、运维端点保持；竞争 Advice 拒绝，收窄覆盖允许；非 Servlet 及 committed 响应定向证据 | 未支持协议保留 |
| A07 | 全 reactor 有效版本检查、BOM 无直接依赖、独立 flatten POM 与消费方自有 parent | 非公共制品发布 |
| A08 | 两个仓库外工程及无平台初始缓存，真实 file 仓库获取及实际 JAR SHA，停止 A 后 B 正常 | 非生产部署 |
| A09 | 缺 JAR、错 BOM、skipTests 原生失败；内部包／源码／reactor／systemPath、同 GAV、遗漏消费者反例 | 新接入形态另行验证 |
| A10 | 真实注册、生成合同、独立预期三方校验；首版冻结文件、输入与手改／漂移反例 | 无历史组件，不声称升级兼容 |
| A11 | 本地统一入口覆盖组件、A／B、R1；产品反例与受影响 HTTP 校准回归；扫描告警保留 | 真实 CI 结果由精确候选清单核对；本地其他未改控制面校准未宣称全量重跑 |
| A12 | 有界独立评审及主线语义复核，已复现缺陷保留红灯与修复复验 | 提交推送及真实 Git／delivery 由精确候选清单核对；**精确 SHA 人工验收仍待确认** |

评审修正涵盖：Spring 有效 Advice 覆盖关系、BOM 被模块筛选漏检、组件编译配置守卫、最小健康暴露、打包制品 SHA、安全错误消息及失败日志保存。没有删除原失败断言或把外部下载超时当作产品规则失效。

本地告警扫描未报告 Web 组件漏洞；A／B 使用 Boot 4.0.8 管理的 Tomcat 11.0.24，各报告 11 项（4 critical、5 high、2 medium），与 R1 相同，原生告警未被抑制。现行告警模式不构成生产放行或安全验收。
