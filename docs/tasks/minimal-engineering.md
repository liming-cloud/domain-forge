# 最小真实工程与质量接入任务

## 目标与输入

来源：2026-10-15 本会话用户要求第五层验收收尾后进入第六层，“先明确最小真实工程与质量检查、CI 接入的设计、范围和验收标准，暂不开展业务实现”。最新纠正要求先定义脚手架整体研发结构，并确认同仓库分目录管理代码与文档、分别设置检查入口。整体设计已确认，当前按下述 R1 清单实施迁移与本地验证。第五层精确验收及收尾见[唯一记录](control-plane-verification.md#唯一任务记录)，原最小工程设计的实施批准不代替当前结构决定或工程验收。

服务于[产品目的](../product/product-definition.md#产品目的)与[持续研发路线](../product/rebuild.md#逐层产出)：以真实工程承接有效质量检查，后续能力按单元落实，不扩大为一次交齐所有平台功能。

## 当前范围与退出条件

2026-10-15 用户曾确认：“确认第六层首个单元的设计、范围及 E01—E12 验收标准，继续推进实施：先核查并固定版本与环境，再完成最小真实工程、质量检查和 CI 接入及验证，暂不扩展业务功能。”确认对象为提交 `146c154` 的设计正文，随后形成 `ce20ca6` 技术候选；工程验收尚未获得。

结构调整来源为用户指出“顺序有问题，脚手架整体的研发结构需要定义好，将代码和文档分开管理”，并选择“同一仓库分目录管理，分别设置检查入口”。当前顺序调整为：整体结构与依赖设计、单元落位与迁移设计、实施、验证、人工验收。已实现候选保留作技术依据，当前不请求按原根目录结构验收；2026-10-16 最新授权允许 NVD 更新复验通过后提交推送 R1 候选及核对真实交付证据，不允许写入验收通过、进入 R2 或业务扩展。整体结构的目录、组件／服务／模板映射、检查责任及 S01—S06 均以新设计中的已确认与待确认状态为准。

设计批准、实际检查通过、候选提交和工程人工验收分别记录；正式验收前不写入 acceptance。

## 前置设计遗漏与整改

发现 `STRUCTURE-ORDER-01`：上一轮用最小 Boot 技术工程代替了其依赖的整体研发结构设计，并在 `ce20ca6` 的普通评审中给出“规范轴无未解决阻断项”。用户指出顺序和 DDD 落实问题后，核对现有正文确认该结论不充分。责任在执行与语义评审遗漏，不以新增规范或重新归咎需求不明确替代整改。当前状态：设计遗漏已由 D01—D04 及 S01—S06 的明确确认关闭；迁移实现、验证和工程验收作为 R1 责任继续跟踪。

| 已有要求 | 实际执行与遗漏 | 本轮整改及可核对的出口 |
|---|---|---|
| [产品具体设计顺序](../product/product-definition.md#已确认的产品边界)：总体及能力设计后再研发 | 已设计并实现最小技术探针，但未先明确平台制品、服务、模板的整体关系 | 在[整体结构](../design/development-structure.md)先列交付映射、依赖与研发顺序，按本次确认及 R1 清单迁移 |
| [模块组织原则](../standards/system-design.md#逻辑模块与依赖门禁)：应用内部默认按包；对外组件／服务交付分别判断 | 把“不预建空模块”扩大为“尚无独立组件调用方，可推迟平台结构”，忽略产品已经要求版本化复用 | 为真实产品调用方设计组件与制品，仍按单元创建实际实现，分别核对上下文、构建、制品、部署边界 |
| [领域识别](../standards/requirements-and-domain.md#领域与上下文识别)：先语义、所有者与场景，再映射结构 | 引用了 DDD 规范，但未形成具体上下文关系、数据所有权和翻译责任 | 补 IAM 与项目上下文的关系、关键场景及代码映射；完整战术模型仍在 IAM 单元设计，不宣称当前已完成 |
| [前置决定](../workflow/task-execution.md#计划形成与滚动更新)：在实现依赖它之前查明 | 先固定工程落位并完成 CI，再补整体结构，形成返工 | 依赖顺序调整为总体结构、单元设计、实施、验证、人工验收 |
| [评审责任](../workflow/task-execution.md#责任与人工决定)：Agent 负责普通语义评审 | 链接、源码散列、命中清单和真实检查均未发现整体前置遗漏，Agent 仍给出过满结论 | 普通评审明确写出上位要求、方案依据及未解决决定；结构检查不能替代这项判断 |
| 本轮新增的代码／文档分目录决定 | 原文并未规定必须采用 `code/`，不能倒推为原目录违反既有路径规定 | 依用户最新决定设计分区和迁移，按本次授权实施，不改写此前批准来源 |

原构建、测试、CI、delivery 结果仍是原精确候选的真实技术证据。原 `candidate-review-final.json` 等评审中的总体规范符合性结论不再作为推进依据，本节保留纠正关系，不篡改或删除原始材料。第五层验收不受本项纠正影响；第六层 acceptance 与 closing 继续为空。

整改出口为 D01—D04 的具体方案获得适用决定，且评审能逐项解释本表要求如何落实。它不等于迁移实现通过；设计确认后的代码、检查入口与新候选仍须重新验证。本轮已获 R1 实施及验证后交付授权；不扩展业务功能，R1 人工验收仍未获得。

## 结构确认与 R1 授权

- 来源：2026-10-15 本会话用户原文：“确认 D01—D04 推荐方案及 S01—S06。继续推进，先落实确认记录、明确 R1 迁移清单和验证范围，再按既有规范完成代码与文档分区迁移及适用验证；暂不扩展业务功能或提交推送。”
- 确认对象：[整体研发结构](../design/development-structure.md)的 D01—D04 推荐方案与 S01—S06 标准；写入本确认记录前正文 SHA-256 为 `253d82baae7354f8ebe31b58dc5e3687170bb9c1da57e7f626a5b640223d3792`。该摘要是原确认输入，后续路径调整不改写它。
- 实施起点：`ce20ca6232db07315d11a72ecf612872e3c47845` 加本轮已确认的未提交结构设计。任务 `base` 保留原工程起点用于完整责任选择，不抹去前序实现；R1 比较与迁移证据单独存放 `.run-artifacts/r1-migration/`。
- 2026-10-15 的确认是设计与实施授权，不是 R1 实现验收；当时禁止提交推送。`acceptance`、`closing` 继续为空，不进入 R2。
- 2026-10-16 交付授权，用户原文：“继续推进，先处理 NVD 资料更新 404 并完成复验，保留必要检查和断言；验证通过后允许提交推送 R1 候选，补齐真实 Git／CI 和 delivery-evidence 核验，更新人工验收清单，暂不写入验收通过、不进入 R2 或扩展业务功能。”本条更新当前 Git 操作边界，不改写前次确认输入或人工验收状态。
- 2026-10-16 Maven 结构纠正：用户指出“maven聚合结构还需要调整，parent不应该直接是springboot 4”，随后明确选择“采用内部 parent＋Boot BOM（推荐）”。确认内容为 `code/pom.xml` 同时承担内部 parent 与聚合，导入 Boot 4.0.8 BOM 并显式管理构建插件，探针继承内部 parent，外部消费项目仍可使用自己的 parent。此次修正从 `235a2c023a45b905199655d091366d05e09a604a` 开始，属于 R1 构建结构收敛；既有 R1 验证后交付授权继续适用，人工验收不由设计确认代替。

## R1 迁移清单与验证范围

本清单先于迁移固定。仅迁移实际已有内容，保持版本、源码、运行合同和必要断言。

| 项目 | 精确变更与验证 |
|---|---|
| 文档正文 | `README.md` 产品正文 → `docs/product/product-definition.md`；`REBUILD.md` → `docs/product/rebuild.md`。保留 PRODUCT／REBUILD 稳定 ID、原确认摘要与来源；根 README 改为导航，AGENTS 链接及全部相关相对引用同步更新，索引由公开命令生成 |
| 真实探针 | 根 `pom.xml`、`src/` → `code/verification/boot-smoke/`，字节对照；不改名为 IAM 或业务模板，不增加空组件／服务／模板 |
| 构建入口 | 原迁移将 Wrapper 原样迁入 `code/`，新增仅聚合实际探针且不被继承的 POM；2026-10-16 另行确认改为内部 parent＋聚合及 Boot BOM。固定聚合范围，拒绝空模块、遗漏模块、未支持配置或链接；真实 reactor verify 的观察对象必须精确对应聚合和探针。外部消费项目不被要求继承内部 parent |
| 输入与原生适配 | `tools/product_gate/{environment,build,advisory}.py` 适配仓库根、代码聚合根与实际模块；保留原编译／类型／依赖／JAR／测试覆盖校验及原生扫描，不复制另一套质量规则 |
| 路由与 CI | `catalog.json`、必要选择逻辑、工作流缓存输入及 `.gitattributes`／`.gitignore` 适配；旧路径仅保留删除差异的分类责任，不接受继续使用。文档普通修改单独检查，规则／合同／共享工具改变触发产品与适用机制检查；未知路径明确拒绝 |
| 使用与回归 | 更新设计中的真实命令。先以新增定向反例证明新目录、聚合遗漏及跨区漏检会失败，再修正适配；迁移只做字节对照，不为搬运新增镜像测试。完整运行控制面与 delivery 机制测试、工作流守卫及产品入口（含全部既有反例）；必要校准按实际变更选择 |
| 证据与语义 | 固定迁移输入快照，核对 S01—S06 与适用 E01—E12、上位规范和原 STRUCTURE-ORDER-01 整改。保存本地结果及实际耗时；旧 SHA 的真实 CI／delivery 不作为新布局通过。新候选真实 Git／CI／delivery 按 2026-10-16 授权执行，人工验收仍单独保留 |

执行入口保持仓库根 `python3 -B tools/control_plane/control_plane.py check` 与 `python3 -B tools/product_gate/verify.py --project .` 分别承担文档和产品责任；开发构建使用 `code/mvnw -f code/pom.xml verify`。当前聚合只包含一个真实探针；后续增加实际模块必须同时扩展登记和原生验证范围，不由 R1 推定已支持任意多模块产品。

## R1 本地结果与剩余责任

`235a2c023a45b905199655d091366d05e09a604a` 已完成原 R1 候选交付，[CI 38828454659](https://github.com/liming-cloud/domain-forge/actions/runs/38828454659) 的 17 个 job 通过，公开 delivery 为 `candidate-checked`，人工验收仍为空；原材料保留在 `.run-artifacts/r1-delivery/`。本轮内部 parent＋Boot BOM 修正改变构建输入，原 CI 与七个文件字节一致的迁移证据只证明原候选，不能代证新候选。

本次修正清单：同步上述设计与确认记录；先增加直接 Boot parent 被拒绝的失败用例；修改内部 parent、探针继承和产品模型守卫；复用全部既有反例并补父级编译配置、BOM／插件漂移及漏重新打包覆盖；实际验证聚合构建、子模块直接构建、依赖组合和原运行合同。完成后运行完整产品入口、文档和适用控制面回归，再按已有授权提交推送并核验精确 Git／CI／delivery。新证据集中于 `.run-artifacts/r1-parent/`，人工清单仍覆盖 S01—S06 与 E01—E12，不进入 R2。

2026-10-15 已按清单完成物理迁移及适配；本地完整产品入口五项检查通过，21 项产品门禁测试及 1 项真实 HTTP JUnit 通过，控制面／delivery 机制 144 项回归通过（其中 7 项为 delivery 协议检查，不能与独立工作流守卫混称）。该次固定输入复验与逐项语义结论保留于 `.run-artifacts/r1-migration/verification-summary.md`。2026-10-16 补做的独立工作流守卫 7 项、资料配置检查 3 项均通过，新一轮候选证据集中于 `.run-artifacts/r1-delivery/`。

| 标准 | 当前结论、证据与边界 |
|---|---|
| S01、S03 | 已确认的能力／上下文／制品／部署映射继续有效；R1 仅落位技术探针与内部聚合，未创建空服务、组件、模板，也未实现 IAM 模型。原生源码、架构及依赖检查通过；未来真实组件消费仍在 R2 以后验证 |
| S02 | PRODUCT／REBUILD ID 不变，正文迁入 `docs/product/`，根 README 仅导航；索引与链接检查通过。7 个原应用及 Wrapper 文件字节一致，官方脚本执行位保留；产品、规范及历史验收正文只更新必要链接，原始确认摘要不变 |
| S04 | 迁移后代码、POM／Wrapper、实际产品设计／合同、规范正文和共同工具触发真实产品责任，普通任务文档与根导航可独立检查。新增反例先失败后通过；未知路径、旧根输入、空／额外模块、漏 reactor 模块和链接输入均拒绝 |
| S05 | macOS arm64 固定 JDK／Maven／Python 上，新布局实际聚合构建、必需测试、JAR 覆盖、源码／架构、原生依赖扫描、健康／端口／退出及全部产品反例通过；本地与 CI 使用相同仓库根入口，CI 缓存路径已同步。候选提交前不预记 Linux 真实 CI 及精确 Git／delivery 通过；提交后的事实由本节指定的候选清单绑定，不沿用 ce20ca6 绿灯 |
| S06 | D01—D04 及 S01—S06 的确认来源已记录；STRUCTURE-ORDER-01 的设计遗漏整改关闭，实现结果与工程人工验收分开。当前仅授权 R1 候选验证与交付，不写入验收通过、不进入 R2 或业务实现 |

2026-10-15 原生漏洞资料更新遇到 NVD 官方增量文件 HTTP 404，原命令和独立 HTTP 状态核对保留于 `.run-artifacts/r1-migration/`；该次使用既有原生数据库重新扫描，报告 Tomcat 11.0.24 的 11 条告警，不声称资料更新成功。2026-10-16 同一官方 URL 已恢复 HTTP 200，原有 `dependency-check:13.0.0:update-only` 实际执行成功，耗时 150.823 秒，日志确认更新完成及 `BUILD SUCCESS`。成功日志与资料摘要见 `.run-artifacts/r1-delivery/advisory-prepare.json` 和 `advisory-preparation-success.log`。无需修改地址、原生配置、版本、预算或失败断言；只能确认外部可用性恢复，不能据此断定 NVD 服务端根因。新候选随后用更新后的资料重新扫描，当前告警以新原生报告为准；不复用旧报告，不把正常返回或告警保留政策解释为零风险或生产放行。

适用本地验证选择依据：本次改动集中于输入发现、聚合适配、文档路径、路由和 CI 编排，已运行完整产品入口及控制面／delivery 回归；`tools/engineering_gate/` 校准实现未变，规范正文除链接外未变，不重复运行无改动的全部数据库／权限／运行夹具。本次没有取得完整 `control_plane run`、全部维护分组的新证据；后续 CI 的保守维护选择保持不变，本地适用验证不冒充整套远端通过。

当前按 2026-10-16 授权完成资料更新和固定输入复验，再提交推送候选。精确 SHA、真实 CI run／attempt、逐 job／step 核对与公开 delivery 回执填入 `.run-artifacts/r1-delivery/acceptance-checklist.md`，不得用旧候选或自报成功替代。该清单只汇总材料和 Agent 结论；工程 `acceptance` 与 `closing` 继续为空。

人工清单逐项覆盖 S01—S06、适用 E01—E12 和原生告警，明确区分本地验证、远端技术证据与人工决定。需要用户最终确认的是精确 R1 候选的结构迁移实现及既定范围内技术结果；D01—D04 的既有设计批准不重复索取，也不把未来组件、IAM、模板、部署或性能责任算作本单元已完成。当前没有 R1 验收通过结论或 R2 准入。

## 验收材料与剩余责任

技术候选 `ce20ca6232db07315d11a72ecf612872e3c47845` 已完成 macOS／Linux 真实产品检查、每端 17 项产品门禁测试及 1 项 HTTP JUnit；本地控制面回归 143 项、工作流回归 7 项通过。[真实 CI 38720292666](https://github.com/liming-cloud/domain-forge/actions/runs/38720292666) 的 17 个作业通过，公开 delivery 核验为 `candidate-checked`。这些结果对应迁移前精确输入，不证明新结构已实现或获验收；原完整证据目录为 `.run-artifacts/minimal-engineering/`。后续结构迁移须重新绑定候选并验证，原候选人工验收保持未获得。

真实 CI 曾在首次下载 NVD 全量历史资料时达到准备上限，记录为环境准备未完成；已补齐准备日志保留，并使用同配置原生数据库缓存作为输入后完成原生更新及当前工程扫描。必要断言及预算均保留，未复用扫描成功报告。

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
    "DEVELOPMENT-STRUCTURE#状态与依据",
    "DEVELOPMENT-STRUCTURE#ddd-边界与代码映射",
    "DEVELOPMENT-STRUCTURE#仓库分区与管理边界",
    "DEVELOPMENT-STRUCTURE#产品交付单元与依赖",
    "DEVELOPMENT-STRUCTURE#身份权限部署与调用方案",
    "DEVELOPMENT-STRUCTURE#模板与版本消费方案",
    "DEVELOPMENT-STRUCTURE#分别检查与关联验证",
    "DEVELOPMENT-STRUCTURE#研发顺序与当前迁移",
    "DEVELOPMENT-STRUCTURE#推荐决定与待确认事项",
    "DEVELOPMENT-STRUCTURE#结构验收条件",
    "DEVELOPMENT-STRUCTURE#资料核对与结论范围",
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
    "SOURCE#已确认的测试复杂度基线",
    "ENGINEERING-TASK#前置设计遗漏与整改",
    "REQUIREMENTS#领域与上下文识别",
    "REQUIREMENTS#建模深度",
    "REQUIREMENTS#上下文模型与战术设计",
    "SYSTEM#组件与扩展设计",
    "SYSTEM#本地事务与跨上下文一致性",
    "SYSTEM#领域与持久化对象分离",
    "ACCESS#已确认的身份与会话基线",
    "ACCESS#已确认的账号与权限变更生效原则",
    "ACCESS#已确认的密码变更与会话原则",
    "ACCESS#已确认的安全管理审计失败原则",
    "PRODUCT#身份权限交付形态",
    "PRODUCT#首版通用能力清单",
    "PRODUCT#实现支持与扩展范围",
    "PRODUCT#缓存与额外通用能力",
    "PRODUCT#应用侧可观测能力",
    "PRODUCT#基础-api-网关",
    "PRODUCT#运行与部署交付",
    "PRODUCT#性能验收边界",
    "ENGINEERING-TASK#结构确认与-r1-授权",
    "ENGINEERING-TASK#r1-迁移清单与验证范围",
    "ENGINEERING-TASK#r1-本地结果与剩余责任"
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
      "criterion": "先核对整体结构、代码与文档分区、制品及依赖、检查责任和研发顺序；再复核最小工程 E01—E12，待确认结构不得写为批准，历史技术证据不代替新输入或人工验收"
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
  "frontier": "2026-10-16 用户明确验收 R1 精确候选 6f6cea347e77dd4b6e6e9077a6086a77effa1a55；原始确认、S01—S06／E01—E12 范围及证据见本记录 acceptance。当前只完成一次限定验收记录提交与真实 Git／CI／delivery 收尾；收尾通过后进入 R2 设计，组件、平台 BOM 与两个独立消费项目须先明确设计、范围及验收标准，暂不实现业务。此前正文为对应时点的历史过程，不覆盖本唯一记录。",
  "pending": [
    "核对本次限定 doc: 记录提交的实际远端、对应 CI 和 delivery；未取得结果不预写 closing 通过",
    "收尾完成后按本次授权细化 R2 设计；R2 实施仍须确认具体设计、范围与验收标准"
  ],
  "deferred": [
    "IAM、Nacos、数据库和其余首版通用能力按后续单元实现",
    "外部制品发布、独立消费项目、部署升级及性能尚未执行"
  ],
  "acceptance": {
    "actor": "本会话用户／项目验收责任人",
    "date": "2026-10-16",
    "source": "ENGINEERING-TASK#唯一任务记录",
    "functional_sha": "6f6cea347e77dd4b6e6e9077a6086a77effa1a55",
    "scope": "R1 代码与文档分区迁移、最小真实工程及质量／CI 接入，包含内部 Maven parent＋Boot BOM 修正；对应 S01—S06 与 E01—E12。不包含 R2 组件／平台 BOM／独立消费实现、外部制品发布或业务功能。",
    "source_message": "确认当前候选 6f6cea3 的 R1 人工验收通过，完成验收记录和必要收尾，允许提交推送；随后进入 R2，先明确首个可复用组件、平台 BOM 和两个独立消费项目验证的设计、范围及验收标准，暂不开展业务实现。",
    "source_conversation": "01a137db-5b64-70c1-9b20-587ab9d0619e",
    "evidence": {
      "ci": "https://github.com/liming-cloud/domain-forge/actions/runs/38833920539",
      "ci_attempt": 1,
      "checklist": ".run-artifacts/r1-parent/acceptance-checklist.md",
      "review": ".run-artifacts/r1-parent/review-final.json",
      "delivery": ".run-artifacts/r1-parent/delivery-final.json",
      "input": "719630146d559f4dc17b94089f771695d680f76740a5da36af52dd64e0d17fb5"
    },
    "limitations": [
      "Tomcat 11.0.24 的 11 项原生漏洞告警依既有告警政策保留，不代表生产放行",
      "后续实际组件、独立消费、IAM、部署升级恢复与性能仍按后续单元验证"
    ]
  },
  "closing": {
    "status": "pending",
    "reason": "本记录提交尚未生成、推送及核对真实 CI；结果按实际 Git／CI 对象与公开 delivery 查询，不预写通过。",
    "functional_run_id": 38833920539,
    "evidence_directory": ".run-artifacts/r1-close/"
  }
}
```
