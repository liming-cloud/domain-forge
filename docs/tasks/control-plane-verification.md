# 文档控制面实施与整体验证路线

当前状态、未完成责任和后续结果只维护于[唯一任务记录](#唯一任务记录)。当前授权包含第五层候选提交推送与真实 Git／CI 核验，不包含业务研发；第五层未经验收，不进入第六层。

## 路线确认记录

- 来源：2026-10-08，本会话用户答复 A，批准目标、范围、依赖、下列五个里程碑及验证出口。
- 原始确认稿 SHA-256：`21de4f1e96144ce452d746effc0ea6bfb5a6f56a98c94fea14d7908880f6ff36`；Git 起点 `b1ed58cf6f2603eb82977cb59e07a2ca204eeccc` 不代表当时未提交的确认稿。
- 批准实施路线不等于机制通过、整套验收或业务研发、提交推送授权。
- 后续授权：2026-10-14，本会话用户明确允许提交并推送当前已验证的第五层控制面候选，补齐真实 Git／CI 证据并运行 delivery-evidence 核验；人工验收单独保留，暂不进入业务研发。CI 整改按已确认的[交付次序](../workflow/task-execution.md#已确认的-git-交付次序)形成正常修复提交，不改写历史。

## 目标与输入

交付能够指导平台研发和消费项目需求研发的控制面：按任务定位目标、设计与规则；可判定遗漏、错误输入及未执行门禁不能报通过；语义评审与真实产品结果责任明确。

输入：[产品成功标准](../../README.md#产品成功标准)、[验收提纲](../../README.md#第一层验收场景提纲)、已确认规范及[第四层流程](../../REBUILD.md#第四层确认记录)。消费者为平台和独立消费项目研发者。隔离工具夹具不作为可运行脚手架、IAM、部署或独立消费证据。

## 范围与依赖

包含文档身份与索引、按需路由、任务唯一记录、全部必需具体校验适配及有效正反例、统一执行与 CI 配置、四类使用路径及必要评审。允许确定适配必需的产品技术基线与合同；不展开完整产品系统结构或业务实现，不导入旧仓库、备份或旧结论。

工具校准环境与产品版本基线分开。已支持范围用真实正反例验证；未知范围报告缺口，不将工具局限改成合法设计的禁令，也不穷举所有框架和语言形式。

## 实施里程碑

| 里程碑 | 退出条件 |
|---|---|
| 1 规则与责任 | 有效规则能定位唯一正文、触发及自动／场景／语义验证责任，未完成项可见 |
| 2 阅读与记录 | 索引、实际变更路由及唯一任务记录可执行；合法轻量任务不过载，遗漏、错误指针、旧输入和未执行被识别 |
| 3 工程机制 | 必要工具及配置固定；合法、目标违规及整改对象真实验证，本地与 CI 使用同一基线 |
| 4 使用路径 | 创建项目、业务需求、平台扩展、缺陷修复的阅读、恢复、覆盖、整改和交付准备可走通 |
| 5 整套验收 | 固定候选、适用结果、必要语义审阅和支持边界齐备；经授权核对 Git／CI 后，请用户验收精确对象 |

## 当前前沿与下一步

按第五层先完成全部具体适配的决定推进：锁定 Boot 4.0 配套线所需的技术与合同，再补齐源码、架构、HTTP／访问、持久化、运行装配和交付核对。结果性正文、人工阅读顺序及四类使用路径继续复用；整改后执行完整适用验证。重大取舍与整套验收仍保留人工责任。

身份、分类、触发和检查只维护于[目录配置](../../tools/control_plane/catalog.json)。进度和缺口只维护于下方机器记录，不在规范、根入口和机制设计中滚动追加过程。准确执行对象以固定输入报告为准。

## 本轮盘点核对

原手工规则盘点已由目录配置及实际检查替代，其中“尚无索引／路由／执行器”的现状描述失效，删除重复盘点正文。规则责任继续由唯一正文及目录配置维护，各层原始审批摘要仍在[重建记录](../../REBUILD.md)。

## 验证与验收出口

- 按需入口定位目标、现行设计与必要规则，人工通读顺序不扩大 Agent 最低清单。
- 正例允许通过；反例因对应违规和诊断失败，整改后恢复。不以无关编译／网络失败、空测试或跳过证明有效。
- 必需环境和当前机制缺口保持未通过；产品延期明确未执行。自动检查不证明全部语义或产生人工验收。
- 提交及收尾遵守[唯一 Git 流程](../workflow/task-execution.md#已确认的-git-交付次序)，本轮暂无执行授权。

以下小节仅保留必要决定与历史证据入口。报告对应旧输入时只作追溯，不作为当前通过。

## 本轮机制复验

治理机制的合法范围、真实临时 Git 差异、引用／指针／覆盖、旧输入、空执行和跳过由[控制面回归](../../tools/control_plane/test_control_plane.py)验证。四类路径在真实配置与当前正文的临时副本中验证按需加载、恢复、目标／引用违规、整改及交付准备，不执行业务场景。项目 Git 不被测试写入；结构结果不代替语义和验收。

## 本轮工程工具校准

有效工具约定见[工程机制](../design/engineering-gates.md)。已撤回的安全策略不再保留并行正文或旧工具；共性错误由现行规则和有效反例承接，不从外部恢复材料导入输入。

## 安全检查范围收缩

来源：2026-10-09 本会话用户要求仅确定版本，接入 Maven 依赖扫描并提示安全告警。现行要求见[构建规范](../standards/technology-and-build.md#已确认的依赖漏洞门禁原则)。

撤回高等级自动阻断、自研评分／资料治理、上游规则审计及工具发行审批等前置要求，不撤销源码、架构或有效告警验证。过期安全探索记录已清理。

## 基础 Maven 告警验证

正常扫描、已知漏洞告警、缺失资料失败及新工程整改已接入[原生回归](../../tools/engineering_gate/test_advisory_runtime.py)。当前必须重新执行，早先独立 POM 结果不作为新输入证明。

## 真实编译范围验证

真实 Maven 源码与类产物范围及目标拒绝由[范围回归](../../tools/engineering_gate/test_maven_scope.py)验证。历史报告入口：`.run-artifacts/maven-scope-verification-final.json`。

## 编译用途与源码检查验证

真实编译用途驱动注释、格式与数值检查；原生范围和固定分组验证见[源码绑定回归](../../tools/engineering_gate/test_maven_source.py)。历史报告：`.run-artifacts/maven-source-verification-final.json`。

## 编译类型与模块合同验证

实际产物、源码、模块与精确合同绑定见[架构回归](../../tools/engineering_gate/test_maven_architecture.py)。历史报告：`.run-artifacts/maven-architecture-verification-final.json`；完整语义与动态范围不由此证明。

## 合同方法与源码说明验证

实际生产合同的方法／构造器说明位置、签名和继承来源见[方法回归](../../tools/engineering_gate/test_method_contracts.py)。历史报告：`.run-artifacts/method-contract-verification-final.json`；说明准确性和内部关键行为仍须评审。

## 阶段验收责任决定

来源：2026-10-10 本会话用户选择 A，分离第五层机制验收与第六层真实产品结果验收；2026-10-12 用户进一步选择 B：“第五层先完成全部具体适配”。第五层包含绑定具体框架、接口合同、数据库迁移和运行装配的校验器，为此先确定必要技术与合同；第六层仍严格验收真实产品构建、扫描、运行及消费结果。未完成不记通过，不以延期消除当前机制缺口，不授权业务研发或 Git 操作。唯一规则见[阶段边界](../standards/testing-and-verification.md#执行与证据边界)。

## 产品框架代际决定

来源：2026-10-12 本会话用户选择 A。产品方向采用 Spring Boot 4.0 + Spring Cloud 2025.1 + Spring Cloud Alibaba 2025.1 配套线，MyBatis-Plus／Sa-Token 使用对应 Boot 4 适配。确切版本依据官方维护与兼容资料，并以真实组合校准锁定；代际批准不表示具体版本、组合运行或产品验收已经通过，不授权业务研发或 Git 操作。唯一技术要求见[主技术栈](../standards/technology-and-build.md#已确认的主技术栈)。

## JSON 外壳决定

来源：2026-10-12 本会话用户选择 A。普通业务 JSON 固定 `code`、`message`、`data` 三字段，稳定字符串标识，无数据为 `null`，不增加 `success`；`SUCCESS` 表示成功，`ACCEPTED` 仅表示受理。HTTP 状态原则及下载／流式／运维端点例外不变。唯一规则见[响应外壳](../standards/api-and-errors.md#已确认的-json-响应外壳)。本项不表示实现、验证或整套验收通过。

## HTTP 凭证传递决定

来源：2026-10-12 本会话用户选择 A。首版仅从 `Authorization: Bearer <token>` 取得 HTTP 登录令牌，关闭其他默认取令牌渠道；唯一规范见[HTTP 凭证传递](../standards/security-and-access.md#已确认的-http-凭证传递)。本项不授权产品 IAM 研发或 Git 操作，也不代表 Redis、权限或整套访问门禁已验证。

## 密码算法决定

来源：2026-10-13 本会话用户选择 A，默认采用 Argon2id。唯一规范见[密码与凭证保护](../standards/security-and-access.md#密码与凭证保护)。本项只固定算法，不将隔离校准参数视为生产负载基线，不实现登录、初始化管理员或操作 Git。

## 原生注解默认决定

来源：2026-10-13，本会话用户选择 A：“采用 Sa-Token 原生注解作为默认方式，继续推进第五层门禁和有效反例验证，暂不进入业务研发或提交推送”。默认声明、统一默认拒绝及其他方式的验证责任只维护于[访问规范](../standards/security-and-access.md#已确认的业务接口访问策略)；批准默认机制不表示访问门禁或整套控制面已完成。

## Flyway 启动迁移决定

来源：2026-10-13，本会话用户选择 A：“应用启动时自动执行”；应用进程须持有迁移凭据。唯一规范见[迁移工具](../standards/persistence.md#已确认的迁移工具)。本项只固定第五层校准合同，不新增离线管理员初始化工具，不授权业务研发或 Git 操作，不表示迁移、持久化或整套控制面已验证。

## 多数据源接入决定

来源：2026-10-13，本会话用户选择 A：“采用 dynamic-datasource；数据源按配置别名接入，基础设施适配器用 `@DS` 选择”。唯一规范见[事务与多数据源](../standards/persistence.md#事务与多数据源)。本项不引入分布式事务，确切版本、路由与事务边界须实测；不授权业务研发或 Git 操作，不代表持久化门禁或整套控制面已完成。

## 事务前置路由决定

来源：2026-10-13，本会话用户选择 A：“技术装配前置路由；应用保留 `@Transactional`，装配层按明确的用例与资源绑定，先选库再启动事务，集中验证切面顺序”。唯一规范见[事务与多数据源](../standards/persistence.md#事务与多数据源)。本项只固定第五层校准合同，不引入分布式事务，不授权业务研发或 Git 操作。

## 默认异常回滚决定

来源：2026-10-13，本会话用户选择 A：“统一默认异常回滚，特殊提交显式声明”，采用 Spring 原生配置，包含受检异常。唯一规范见[事务与多数据源](../standards/persistence.md#事务与多数据源)。本项只固定第五层校准合同，不授权业务研发或 Git 操作，不代表完整持久化或控制面已验收。

## 官方 Wrapper 启动验证

固定官方脚本的真实启动、分发摘要拒绝和新缓存整改见[Wrapper 回归](../../tools/engineering_gate/test_maven_wrapper.py)。只验证 POSIX 启动，不证明产品构建或 Windows 执行。

## 真实生命周期验证

固定 `verify` 的真实编译、必需测试、JAR、版本／约束和输入检查见[生命周期回归](../../tools/engineering_gate/test_maven_lifecycle.py)。原生成功但测试漏选、跳过、漏类或依赖范围不固定仍须拒绝；不冒充真实产品发布。

## 依赖告警当前复验与 CI 前提

来源：2026-10-11 本会话批准独立、有界的原生 update-only 准备并缓存，常规校准预算不变。独立准备 job 30 分钟、原生进程 25 分钟；常规 job 10 分钟，不新增账号或远端操作权限。

来源：2026-10-14 本会话用户确认，允许将原固定四组拆成更小的固定职责组；保留全部检查、断言、资料准备及每组 10 分钟预算。只修改本地 CI 配置，不授权提交、推送或触发远端运行。配置与守卫见[统一执行](../design/engineering-gates.md#统一执行与证据)。

来源：2026-10-14 续接时，用户明确“maven下载超时很正常，不作为ci失效门禁”。下载超时按依赖准备的环境事实记录，不单凭该现象认定 CI 编排失效；未执行的校准仍不记为通过。后续预算按[最新收敛决定](#执行预算收敛决定)随实测负载调整，必要检查和断言保持。

本地空资料准备成功证据：`.run-artifacts/advisory-data-feed-prepare.json`；原生扫描回归：`advisory-runtime-fresh-feed-green.log`。配置及使用路径见[唯一告警机制](../design/engineering-gates.md#基础-maven-依赖安全告警)。本地结果不证明资料实时完整或远端 CI 已成功。

## 构建类路径绑定与依赖入口确认

来源：2026-10-12 本会话用户选择 A：官方能力入口承诺的 API 可用，偶然传递库须显式声明，BOM 不算能力入口。唯一要求见[依赖规范](../standards/technology-and-build.md#构建与依赖管理)。

实际类路径／来源、源码保留期引用、能力入口与机器 JAR 约束由[架构回归](../../tools/engineering_gate/test_maven_architecture.py)及[依赖声明回归](../../tools/engineering_gate/test_dependency_declarations.py)承接。历史报告：`.run-artifacts/dependency-declarations-system-verification-final.json`。

## 应用层框架边界确认

来源：2026-10-12 本会话用户选择 A，允许应用层必要装配／事务元数据，领域层保持框架无关；不允许借许可调用技术客户端。唯一规则见[应用层边界](../standards/system-design.md#出口端口与平台能力复用)。

限定反例见[职责回归](../../tools/engineering_gate/test_role_boundaries.py)。真实事务和装配在产品阶段验证；最新历史完整对象：`.run-artifacts/architecture-budget-verification-final.json`。已有预算超时记录仍为失败，不以分组通过掩盖完整结果缺口。

## 执行预算收敛决定

来源：2026-10-14 本会话用户明确“预算按实际负载合理调整，保留必要检查和断言，优先补齐剩余具体适配、使用路径和语义评审并完成验证，暂不进入业务研发或提交推送”。本决定取代此前固定秒数不得调整的限制；不删减必要检查、断言、环境前提或人工验收。预算用于有界执行，超时记录为未完成，不能单凭耗时推导产品质量失败。当前 79—86 秒的控制面负载采用 180 秒命令上限留出余量，HTTP 首次依赖准备独立采用 600 秒。CI 分组按真实负载核对，守卫保留覆盖和有界性，不冻结历史数值；远端结果仍须另行授权验证。

常规 CI 每组调整为 20 分钟，新增一个运行适配职责组，保留完整公共准备和全部登记命令。依据为本地 HTTP／运行准备约 120 秒、控制面约 79–86 秒及新增 Nacos／观测场景负载，留出冷缓存余量；这不是远端耗时已验证的结论。

## 门禁收敛与上下文边界

来源：2026-10-12 本会话用户同意门禁分层，重点守住设计、架构、技术和领域边界，避免长期漂移与无关上下文累积；随后要求结果性规则、人工阅读顺序及文档瘦身。

执行[分层门禁](../standards/testing-and-verification.md#门禁分层与防漂移)及[渐进加载](../workflow/document-control.md#任务上下文与增长控制)。保留有效规则与全部必要验证；已证伪／过期材料核实后清理，不将旧输入的真实报告指为造假。定向红绿证据：`.run-artifacts/control-plane-convergence-red-green.json`；它不代替当前完整报告和人工审阅。

## 日常评审责任决定

来源：2026-10-12 本会话用户选择 A。普通任务由 Agent 提供源码及场景证据进行语义评审；重大目标、架构边界、规范或风险取舍，以及完整单元验收仍由人工决定。自动门禁及整套控制面的最终人工验收不减少，不授权业务研发或 Git 操作。唯一规则见[任务责任](../workflow/task-execution.md#责任与人工决定)，记录接口见[控制面命令](../design/control-plane-mechanism.md#公开命令与记录字段)。

## 唯一任务记录

本块维护任务事实；索引只作派生。报告保存于忽略的运行目录，不随每次运行追加正文。

```control-plane-task
{
  "id": "control-plane-verification",
  "type": "governance",
  "base": "b1ed58cf6f2603eb82977cb59e07a2ca204eeccc",
  "goals": [
    "PRODUCT#产品成功标准",
    "PRODUCT#第一层验收场景提纲"
  ],
  "unit": "CONTROL-TASK#目标与输入",
  "consumers": [
    "平台研发 Agent",
    "独立消费项目研发 Agent"
  ],
  "scopes": [
    "access-state-calibration",
    "account-change",
    "audit",
    "authorization",
    "configuration",
    "credentials",
    "data-query",
    "data-write",
    "dependency-risk",
    "documentation",
    "failure",
    "governance",
    "http-consumer",
    "identity",
    "java-methods",
    "java-source",
    "login-guard",
    "multi-datasource",
    "nacos",
    "observability",
    "persistence",
    "planning",
    "research",
    "runtime",
    "source-coverage",
    "tests",
    "transaction"
  ],
  "design_refs": [
    "CONTROL-DESIGN#方案比较与选择",
    "CONTROL-DESIGN#最低清单与实际变更",
    "CONTROL-DESIGN#执行与证据",
    "ENGINEERING-DESIGN#工具选择与支持边界",
    "ENGINEERING-DESIGN#用途与合同识别的落实方案",
    "ENGINEERING-DESIGN#字节码与直接合同的限定校准",
    "ENGINEERING-DESIGN#真实-maven-编译范围校准",
    "ENGINEERING-DESIGN#真实编译范围与源码检查绑定",
    "ENGINEERING-DESIGN#真实编译与模块合同绑定",
    "ENGINEERING-DESIGN#源码依赖声明与能力入口校准",
    "ENGINEERING-DESIGN#默认职责与技术注解校准",
    "ENGINEERING-DESIGN#实际合同方法与源码说明",
    "ENGINEERING-DESIGN#真实-http-合同校准",
    "ENGINEERING-DESIGN#真实-sa-token-凭证校准",
    "ENGINEERING-DESIGN#原生访问策略校准",
    "ENGINEERING-DESIGN#真实-argon2id-密码校准",
    "ENGINEERING-DESIGN#启动-flyway-迁移校准",
    "ENGINEERING-DESIGN#mybatis-plus-与动态数据源校准",
    "ENGINEERING-DESIGN#官方-wrapper-启动机制校准",
    "ENGINEERING-DESIGN#真实-wrapper-构建生命周期校准",
    "ENGINEERING-DESIGN#基础-maven-依赖安全告警",
    "ENGINEERING-DESIGN#持久化具体适配",
    "ENGINEERING-DESIGN#访问控制具体适配",
    "ENGINEERING-DESIGN#同次构建的源码与架构组合入口",
    "ENGINEERING-DESIGN#真实-http-消费具体适配",
    "ENGINEERING-DESIGN#运行装配与权威配置具体适配",
    "ENGINEERING-DESIGN#原生观测具体适配",
    "CONTROL-DESIGN#交付证据具体适配"
  ],
  "verification": [
    {
      "check": "doc-integrity",
      "criterion": "当前正文、分类索引、小节引用和唯一任务事实一致，合法对象通过，遗漏或错误对象被目标诊断拒绝"
    },
    {
      "check": "control-plane-tests",
      "criterion": "公共命令的合法正例、真实 Git 范围及隔离目标反例通过有效断言，空测试或任意非目标失败不能当作证明"
    },
    {
      "check": "engineering-probe-tests",
      "criterion": "真实固定工具的合法、目标失败及整改对象通过对应断言；缺失工具、空输入或非目标失败不得冒充通过"
    },
    {
      "check": "engineering-metric-probe-tests",
      "criterion": "保留指标行为与边界的完整校准，固定语义分组不遗漏或重复用例，各自遵守同一执行预算"
    },
    {
      "check": "engineering-input-probe-tests",
      "criterion": "工具、输入及计量覆盖的完整反例真实执行，原断言保留；分组不删除测试、不提高预算"
    },
    {
      "check": "complexity-baseline-tests",
      "criterion": "唯一数值配置的实际边界及整改有效，损坏配置、覆盖尝试和漏采不能冒充正式通过"
    },
    {
      "check": "architecture-probe-tests",
      "criterion": "实际编译和字节码检查核对精确成员访问、声明依赖及嵌套／局部／匿名边界反例；固定分组、环境与 CI 覆盖完整，不增加预算或删断言，局部结果不能冒充构建用途或完整合同闭包"
    },
    {
      "check": "architecture-integrity-tests",
      "criterion": "真实类型、泛型、继承及实际成员合同、模块归属与输入完整性逐项核对；无效声明、缺类、重复／符号链接／损坏对象不能通过，适配器自身质量执行；架构输入同时命中两组与限定支持正文"
    },
    {
      "check": "maven-advisory-configuration-tests",
      "criterion": "固定成熟 Maven 插件、执行绑定、告警与错误区分及基础依赖范围配置真实回归；配置通过不冒充实际产品扫描"
    },
    {
      "check": "dependency-risk-mechanism",
      "criterion": "原生正常、已知漏洞告警、缺失资料失败及整改真实执行；偏离配置和输入不能放行，独立准备及缓存不放宽常规预算，不把隔离机制当作真实 CI 或产品扫描"
    },
    {
      "check": "maven-scope-tests",
      "criterion": "实际 Maven 编译目的、完整输入和类产物逐项核对；排除、跳过、未知用途及陈旧输出等目标反例和整改有效，不证明产品发布或语义用途"
    },
    {
      "check": "maven-wrapper-tests",
      "criterion": "官方脚本在新隔离缓存下载并启动固定 Maven，错误摘要原生拒绝且整改恢复；篡改、缺失、属性偏离及隐式配置被目标诊断拒绝，不将启动当作产品构建"
    },
    {
      "check": "build-mechanism",
      "criterion": "官方 Wrapper 实际 verify，真实编译、断言及 JAR 相互核对；原生失败不能被通过，固定分组覆盖全部用例并与本地及 CI 一致，不证明产品发布"
    },
    {
      "check": "build-test-coverage-tests",
      "criterion": "原生退出 0 但必需场景漏选、跳过、禁用、无发现或忽略失败的目标反例拒绝，独立合同中的必要场景全部执行且整改恢复"
    },
    {
      "check": "build-artifact-tests",
      "criterion": "真实生命周期必需目标遗漏及部分 JAR 漏类分别拒绝；合法对象和新工程整改允许，不以文件或插件名称存在代替实际结果"
    },
    {
      "check": "build-version-tests",
      "criterion": "实际运行及编译目标、必需插件版本及直接有效版本约束均核对；范围不能因最终解析为固定版本放行，基线偏离不能由原生构建成功掩盖，固定分组保留旧场景及预算"
    },
    {
      "check": "build-resolution-tests",
      "criterion": "原生项目及插件约束与实际解析对象匹配；传递范围、快照不能被固定最终版本掩盖；属性解析的机器 JAR 声明即使未使用且 verify 成功也拒绝，正常坐标在新工程实际解析恢复"
    },
    {
      "check": "build-input-tests",
      "criterion": "独立必需测试合同非空、唯一且有效，陈旧输出不覆盖，执行期间维护源码变化使证据失效；不接受调用方降级目标或自行移除必要测试"
    },
    {
      "check": "maven-source-tests",
      "criterion": "真实编译用途驱动类型及结构检查，同包测试不误拒绝，非 public 生产类型及错位注释等目标违规不能漏检；不自报用途或替代完整源码门禁，固定分组覆盖全部绑定用例"
    },
    {
      "check": "maven-source-metric-tests",
      "criterion": "真实生产及测试执行适用数值和格式检查，无显式实现仍校验基线、注释与格式；目标拒绝和真实整改有效，保留全部断言及预算"
    },
    {
      "check": "maven-architecture-tests",
      "criterion": "实际编译类型归属及精确合同由真实对象核对，误报用途、测试合同、未知归属、内部成员及未声明依赖的目标反例和整改有效；固定分组覆盖完整用例"
    },
    {
      "check": "maven-architecture-purpose-tests",
      "criterion": "保留真实模块归属及用途来源反例，生产／测试不由调用方填写；从原组合中按语义固定分组以留出新增实际声明分析的预算，全部用例及断言不变"
    },
    {
      "check": "maven-architecture-binding-tests",
      "criterion": "源码包声明、源文件名及类产物来源唯一关联，合法同名／多类型允许；缺失信息、二进制名冲突、调用方覆盖及环境缺失明确拒绝，不冒充完整架构或方法说明通过"
    },
    {
      "check": "maven-architecture-classpath-tests",
      "criterion": "同次原生类路径及依赖用于类型与源码解析；合法第三方及默认配置允许，遗漏、用途不符、依赖漂移、门禁自身同名库替代及清单引入未观察类型／继承声明者有目标反例，固定分组及 CI 入口一致"
    },
    {
      "check": "dependency-declaration-tests",
      "criterion": "真实源码及依赖身份拒绝偶然传递库，直接声明整改与官方普通／参数化 API 允许；未经校准的组合不误报编码违规，固定分组及 CI 完整一致"
    },
    {
      "check": "dependency-declaration-source-tests",
      "criterion": "源码保留期注解和内联常量不能靠字节码消失漏检，静态导入的继承常量关联实际消费者与源码公开入口，不强加 SDK 内部实现库直接声明；BOM 或 starter 名称不能自动放行，真实新工程整改有效"
    },
    {
      "check": "dependency-declaration-input-tests",
      "criterion": "测试能力入口不能授权生产源码；缺失、部分、矛盾的原生观察及无效能力配置有目标诊断，坏配置在构建前拒绝，恢复后同一公开入口通过；字面或属性解析的机器 JAR 用途在原生构建成功后拒绝，正常坐标在新工程通过"
    },
    {
      "check": "role-boundary-tests",
      "criterion": "真实原生编译后领域框架绑定及应用客户端操作被拒绝，必要装配／事务元数据允许，静态导入枚举后的实际操作不获元数据许可；不冒充事务运行或完整架构通过，固定分组及 CI 入口完整一致"
    },
    {
      "check": "role-boundary-usage-tests",
      "criterion": "组合注解不能隐藏领域绑定，同一注解类型的普通引用不能获得元数据许可；具名及通配导入的内联 SQL 常量即使从字节码消失也关联实际领域消费者并拒绝，目标拒绝与新工程合法整改均有效"
    },
    {
      "check": "role-boundary-ownership-tests",
      "criterion": "职责按实际生产包与编译用途绑定，非公开、局部及匿名实现不能绕过；组件和装配不强套业务分层；完整固定组在原预算内实际执行"
    },
    {
      "check": "role-boundary-input-tests",
      "criterion": "职责 schema、固定配置、注解值关系及原生源码使用位置缺失不能放行；坏声明／配置在构建前拒绝，恢复后原入口通过，缺文件返回结构化诊断；未知外部库或 API 返回校准缺口，不按框架组名误报编码违规"
    },
    {
      "check": "method-comment-tests",
      "criterion": "只检查实际生产合同的精确方法说明，缺失、重载错绑、空正文及错位等目标拒绝和新工程整改有效；固定分组恰好覆盖所有用例"
    },
    {
      "check": "method-source-binding-tests",
      "criterion": "实际 JDK 类型系统签名与字节码精确匹配，合法数组、泛型、嵌套及显式构造器允许，隐式成员不伪造源码说明；适配器自身执行适用结构、格式及数值检查"
    },
    {
      "check": "method-inheritance-tests",
      "criterion": "真实覆盖及说明来源可定位，标记和降低可见性不能免除既有合同；不以机械关联代替继承完整性、内部关键行为或语义评审"
    },
    {
      "check": "persistence-boundary-tests",
      "criterion": "真实 MyBatis-Plus 应用继承、非公开领域映射和公开数据的私有分页字段被目标诊断拒绝，新工程整改恢复，基础设施合法用法允许；不证明数据库或迁移结果"
    },
    {
      "check": "contract-gate",
      "criterion": "第五层完成选定框架的真实注册入口、OpenAPI 生成与发布基线核对；遗漏、响应漂移及重置对照的反例拒绝，整改恢复，不以同源重算证明兼容"
    },
    {
      "check": "http-generation-tests",
      "criterion": "真实 Boot 4 注册、生成、独立场景及实际分派一致；隐藏入口、请求另一入口冒充覆盖等反例目标拒绝后整改通过，适配器自身质量及完整分组与 CI 一致"
    },
    {
      "check": "http-request-tests",
      "criterion": "实际 POST 请求响应、合法继承与组合路由通过；Jackson 转换后响应一致也不能隐藏请求违反 Schema，原生目标诊断和整改有效"
    },
    {
      "check": "http-parameter-tests",
      "criterion": "实际 URI 查询参数与成熟库的路径／参数分别对应，URL 编码内容不被误判为未注册路径"
    },
    {
      "check": "http-drift-tests",
      "criterion": "真实响应不符当前 Schema 拒绝；当前实现与生成物一起改变仍由独立已发布合同拒绝不兼容，整改恢复"
    },
    {
      "check": "http-envelope-tests",
      "criterion": "多余 success、缺失 null data 及把业务拒绝标为成功均在真实 HTTP 中目标拒绝并整改，不靠当前生成物一致掩盖语义漂移"
    },
    {
      "check": "http-input-tests",
      "criterion": "非法 JSON 根结构提供构建前结构化诊断；默认版本、错误发布摘要、少场景、缺工具和错误 HTTP 状态不能通过；目标失败及新工程整改有效"
    },
    {
      "check": "http-scope-tests",
      "criterion": "功能式路由、自定义资源 URL 及非空默认静态映射不能靠注解扫描遗漏放行，全部无效请求标记不能绕过结构校验；校准缺口与产品禁令分开，整改恢复"
    },
    {
      "check": "access-gate",
      "criterion": "第五层核对真实注册入口与有效 Sa-Token 访问策略，合法集中、类型、方法、继承及组合声明可用；缺失、未生效或越权的反例不能通过，不冒充产品 IAM 验收"
    },
    {
      "check": "access-transport-tests",
      "criterion": "同次真实 Sa-Token/Boot 应用验证有效 Bearer Header、缺少或无效 Header 及全部禁止渠道的 401；取消前缀真实越界并拒绝，整改通过；固定分组与 CI 恰好覆盖全部用例，不冒充 Redis 或完整授权"
    },
    {
      "check": "access-configuration-tests",
      "criterion": "原生参数或 Cookie 读取误开须真实展示越界取得身份，再由固定渠道及 HTTP 结果门禁拒绝；新隔离工程整改恢复，不跳过拒绝或响应断言"
    },
    {
      "check": "access-coverage-tests",
      "criterion": "空请求冒充渠道反例、注解存在但实际拦截未执行均拒绝；合法 Header 成功与其他渠道未认证不能被全拒绝或场景伪装替代"
    },
    {
      "check": "access-input-tests",
      "criterion": "访问场景映射缺失、重复、未执行及原生环境缺失在构建前目标拒绝；恢复相同公开入口通过，公开证据不复制凭证，不将未运行或无环境记为成功"
    },
    {
      "check": "password-behavior-tests",
      "criterion": "实际密码 Bean 与独立成熟校验器验证 ASCII、Unicode、长输入及零字节的正确／错误行为；截断、归一化不能放行，适配器质量及固定分组完整一致"
    },
    {
      "check": "password-rejection-tests",
      "criterion": "明文、快速哈希、其他 Argon2 变体、复用盐及伪造 PHC 摘要由对应诊断拒绝，新干净工程整改恢复；不是生产参数或数据库保存证明"
    },
    {
      "check": "password-input-tests",
      "criterion": "实际输出中的本轮合成凭证及其 Base64 表示、无效声明与缺失工具均不能通过；公开报告不复制敏感材料，未运行不视为通过"
    },
    {
      "check": "policy-behavior-tests",
      "criterion": "实际原生匿名、类级登录、继承、组合及 AND／OR 权限声明与独立合同一致；临时未声明探针不污染原装配，能识别单独缺少默认拒绝；观察器质量及分组完整，不产生产品通过"
    },
    {
      "check": "policy-coverage-tests",
      "criterion": "未声明入口由真实匿名和有效身份请求验证默认拒绝，覆盖仍失败；空权限及取消默认拒绝的实际放行不能通过，整改恢复"
    },
    {
      "check": "policy-execution-tests",
      "criterion": "注解存在但未拦截、组合解析未接入或原生处理器被替换时，实际越界响应须被识别；工具不得修补被测应用"
    },
    {
      "check": "policy-input-tests",
      "criterion": "删除或伪装拒绝场景、缺权限主体无有效登录见证、漏掉注册入口及独立权限意图漂移均拒绝；不以无效令牌代替越权主体"
    },
    {
      "check": "migration-behavior-tests",
      "criterion": "真实应用启动执行版本化 SQL，空库初始化、合法重启及保留既有数据的升级通过；观察器不替应用 migrate，完整持久化与产品结果仍未证明"
    },
    {
      "check": "migration-rejection-tests",
      "criterion": "错误 SQL 目录或 Schema、扫描遗漏、关闭迁移及实际类型漂移须按原生运行结果拒绝；资源来源与独立期望绑定，不能只比较版本及文件名"
    },
    {
      "check": "migration-history-tests",
      "criterion": "原生校验拒绝已应用 SQL 改写和隐藏验证，失败后真实查询已提交状态；通过正确历史及新增升级整改，不自动 repair 或 clean"
    },
    {
      "check": "migration-integrity-tests",
      "criterion": "相同行数下的数据漂移、合成凭据输出和编译 SQL 篡改均被拒绝，正确整改恢复；公开报告不包含凭据、SQL 或行值"
    },
    {
      "check": "migration-input-tests",
      "criterion": "独立 SQL 合同、摘要、私有连接与原生依赖缺失拒绝；Docker 覆盖、既有挂载和缺失原生观察不能放行，实际目标仅为本轮临时数据库"
    },
    {
      "check": "persistence-behavior-tests",
      "criterion": "真实 MyBatis-Plus 与动态数据源执行默认及方法覆盖路由，独立查询验证提交、回滚及恢复默认；观察器源码质量和固定分组不遗漏"
    },
    {
      "check": "persistence-routing-tests",
      "criterion": "实际错误选库及未知别名回落均拒绝，原生查询确认写入位置，不从 DS 注解存在推定路由正确"
    },
    {
      "check": "persistence-transaction-tests",
      "criterion": "事务遗漏和资源错配的实际回滚失败被拒绝，整改后独立连接查询确认正确提交与回滚，不用测试事务遮蔽事实"
    },
    {
      "check": "persistence-execution-tests",
      "criterion": "自调用未进入事务、手写结果替代真实 Mapper 均拒绝；不能以注解和固定返回值代替原生执行"
    },
    {
      "check": "persistence-context-tests",
      "criterion": "已绑定事务内切换别名和上下文残留必须由真实连接与后续读取识别，不由工具清除残留使其通过"
    },
    {
      "check": "persistence-binding-tests",
      "criterion": "应用保留事务声明，技术装配前置路由；漏用例绑定、路由晚于事务均真实拒绝，修复后实际连接、提交回滚及上下文恢复正确"
    },
    {
      "check": "persistence-rollback-tests",
      "criterion": "原生统一默认受检异常回滚；遗漏默认配置时独立查询确认错误提交并拒绝，整改恢复；显式声明的特殊异常提交按独立合同允许并验证"
    },
    {
      "check": "persistence-policy-tests",
      "criterion": "默认规则必须来自当前操作实际代理链匹配的事务 Advice；闲置正确 Advisor 不能代证错误运行配置，未校准元数据子类不报通过，原生正确装配整改后恢复"
    },
    {
      "check": "persistence-integrity-tests",
      "criterion": "同数量错误数据、合成凭据输出和生产产物篡改拒绝；公开报告不复制原始数据，输入及原产物保持可核对"
    },
    {
      "check": "persistence-observer-tests",
      "criterion": "观察器不得主动取连接替应用制造拒绝；未校准 Advice 子类或解析器不能冒充默认原生规则，实际装配见证、目标诊断及干净整改均通过"
    },
    {
      "check": "persistence-input-tests",
      "criterion": "不能省略、重复或削弱路由、事务和拒绝场景，不能把回滚期望改成已提交；私有连接、两个实际目标、必需环境和原生观察完整性均验证"
    },
    {
      "check": "datasource-isolation-tests",
      "criterion": "真实 DS 技术注解不能进入应用或领域，降低可见性不豁免；基础设施上的合法用法通过"
    },
    {
      "check": "runtime-mechanism",
      "criterion": "第五层完成选定运行装配的输入、真实入口及场景结果核对，缺失环境、未执行或陈旧结果拒绝；隔离机制校准与第六层产品运行结果分开"
    },
    {
      "check": "delivery-evidence",
      "criterion": "第五层在隔离反例中验证精确 Git／CI 对象、有效人工来源及收尾核对；状态填写不能替代事实，项目实际 Git／CI 操作仍须另有授权"
    },
    {
      "check": "persistence-mapping-tests",
      "criterion": "真实数据库与 Java 往返核对精确数量、时间、空值、枚举、生成键与重建结果，错误转换拒绝并整改"
    },
    {
      "check": "persistence-pagination-tests",
      "criterion": "原生分页核对关联 COUNT、范围、参数绑定、边界和空页；漏范围拒绝并整改"
    },
    {
      "check": "persistence-pagination-plugin-tests",
      "criterion": "原生分页插件缺失不能通过；正确装配整改后通过"
    },
    {
      "check": "persistence-concurrency-tests",
      "criterion": "两个不同实际会话竞争版本与唯一键，核对一个成功者、版本回写及实际状态，失去保护拒绝并整改"
    },
    {
      "check": "persistence-condition-tests",
      "criterion": "自定义更新不能缺少声明的版本条件；同值更新和未匹配分别核对"
    },
    {
      "check": "persistence-batch-tests",
      "criterion": "原生 BATCH 的分批刷新、驱动计数、刷新失败和提交失败均验证真实回滚；部分成功不能当作完成"
    },
    {
      "check": "persistence-migration-tests",
      "criterion": "两个真实资源独立初始化、保留数据重启与升级，核对迁移历史、实际来源与结构"
    },
    {
      "check": "persistence-migration-recovery-tests",
      "criterion": "保留第一资源已提交与第二资源失败回滚的事实，验证不执行 repair 或 clean 的明确恢复"
    },
    {
      "check": "persistence-migration-rejection-tests",
      "criterion": "错误迁移目标不能借用另一资源历史通过，整改后真实运行通过"
    },
    {
      "check": "persistence-migration-provenance-tests",
      "criterion": "相同 SQL 从错误扫描位置执行也须拒绝；正确物理来源整改通过"
    },
    {
      "check": "persistence-adapter-integrity-tests",
      "criterion": "手写结果、缺少原生执行及错误精度不能自证通过，干净整改通过"
    },
    {
      "check": "persistence-adapter-input-tests",
      "criterion": "具体适配合同不可补填期望或省略场景，源码质量、分组、环境及 CI 一致性均验证"
    },
    {
      "check": "persistence-gate",
      "criterion": "同一应用从两个空资源启动真实迁移后，Mapper 路由、提交和回滚必须可用；迁移数据与 Mapper 合同冲突拒绝并整改"
    },
    {
      "check": "persistence-affected-rows-tests",
      "criterion": "固定返回不能掩盖原生零行结果，整改后逐次计数符合合同"
    },
    {
      "check": "persistence-migration-failure-source-tests",
      "criterion": "非迁移启动异常不能冒充脚本回滚，失败资源与独立摘要必须绑定"
    },
    {
      "check": "persistence-policy-source-tests",
      "criterion": "未校准事务元数据来源子类拒绝并整改；独立命令不放宽断言和时间预算"
    },
    {
      "check": "access-state-scope-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessScopeCommands"
    },
    {
      "check": "access-state-owner-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessOwnerCommands"
    },
    {
      "check": "access-state-operation-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessOperationCommands"
    },
    {
      "check": "access-state-session-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessSessionCommands"
    },
    {
      "check": "access-state-expiration-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessExpirationCommands"
    },
    {
      "check": "access-state-changes-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessChangesCommands"
    },
    {
      "check": "access-state-permission-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessPermissionCommands"
    },
    {
      "check": "access-state-role-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessRoleCommands"
    },
    {
      "check": "access-state-password-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessPasswordCommands"
    },
    {
      "check": "access-state-failures-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessFailuresCommands"
    },
    {
      "check": "access-state-throttle-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessThrottleCommands"
    },
    {
      "check": "access-state-audit-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessAuditCommands"
    },
    {
      "check": "access-state-audit-failure-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessAuditFailureCommands"
    },
    {
      "check": "access-state-reconciliation-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessReconciliationCommands"
    },
    {
      "check": "access-state-secret-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessSecretCommands"
    },
    {
      "check": "access-state-redis-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessRedisCommands"
    },
    {
      "check": "access-state-project-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessProjectCommands"
    },
    {
      "check": "access-state-input-tests",
      "criterion": "真实访问具体适配的正常、目标违规拒绝和整改须通过；绑定同次构建、原生环境及固定场景，缺环境或工具失败不算有效反例；对应 AccessInputChecks, AccessToolQualityChecks, AccessStatePartitionChecks"
    },
    {
      "check": "access-state-login-secret-tests",
      "criterion": "实际 HTTP 泄露反例须由 SG_ACCESS_STATE_SECRET 拒绝并整改；只允许成功登录 data.token 交付，本轮新旧摘要及失败输入也须覆盖；对应 AccessLoginSecretCommands"
    },
    {
      "check": "access-state-header-secret-tests",
      "criterion": "实际 HTTP 泄露反例须由 SG_ACCESS_STATE_SECRET 拒绝并整改；只允许成功登录 data.token 交付，本轮新旧摘要及失败输入也须覆盖；对应 AccessHeaderSecretCommands"
    },
    {
      "check": "access-state-new-hash-secret-tests",
      "criterion": "实际 HTTP 泄露反例须由 SG_ACCESS_STATE_SECRET 拒绝并整改；只允许成功登录 data.token 交付，本轮新旧摘要及失败输入也须覆盖；对应 AccessNewHashSecretCommands"
    },
    {
      "check": "access-state-failed-login-secret-tests",
      "criterion": "实际 HTTP 泄露反例须由 SG_ACCESS_STATE_SECRET 拒绝并整改；只允许成功登录 data.token 交付，本轮新旧摘要及失败输入也须覆盖；对应 AccessFailedLoginSecretCommands"
    },
    {
      "check": "source-gate",
      "criterion": "第五层同次真实构建的源码、合同与关键方法机械检查；登记完整性、说明准确性和省略仍须语义评审"
    },
    {
      "check": "architecture-gate",
      "criterion": "第五层同次实际构建的模块合同、职责和依赖组合检查；动态入口由具体运行适配覆盖，语义完整性不由自动检查产生"
    },
    {
      "check": "runtime-nacos-tests",
      "criterion": "真实私有 Nacos、标准配置导入、组隔离、刷新、断连及应用失败恢复"
    },
    {
      "check": "observation-tests",
      "criterion": "原生 SDK 与真实回环接收验证信号、关联、业务分类、故障隔离及恢复操作的新数据"
    },
    {
      "check": "runtime-source-quality-tests",
      "criterion": "本轮实际 Java 观察器及夹具说明、格式和复杂度检查"
    }
  ],
  "frontier": "第五层已在固定输入 cd335810556e6bc186d6452c14b92b1a29658f4a3f8fe180d1498585782f4ad6 完成本地适用验证与普通语义评审，材料见 .run-artifacts/fifth-layer-local-validation。候选已按授权提交推送；真实 CI 暴露 job 级 runner 上下文非法及 setup-java 默认注入隐式 Maven 参数。后续原生资料从空缓存更新达到 25 分钟上限，后续校准未执行；这是准备未完成，不是机制失效结论。本轮限定修复 CI 初始化、失败日志输出并按已批准负载原则调整独立准备预算，补齐回归，保留校准参数拒绝边界与全部断言，真实结果按当前提交及其 CI 对象重新核验，不把旧报告重标为新输入完整通过。",
  "pending": [
    "修复候选仍须对应当前远端 Git、完整 GitHub Actions 运行及 delivery-evidence 实际核验；失败运行保留，不能以总绿灯、协议夹具或旧输入结果代替",
    "整套第五层人工验收及验收后记录收尾仍未完成；候选提交推送与 CI 核验授权不产生验收事实，也不授权进入业务研发"
  ],
  "deferred": [
    "第六层真实产品构建、最终解析组合、发布及依赖扫描；当前无产品 Maven 工程，均未执行，不以配置或隔离校准代替",
    "第六层的真实 IAM、Argon2id 生产工作参数与目标负载、默认组件及公开扩展",
    "两个独立消费项目、部署、升级和性能场景"
  ],
  "extra_refs": [
    "CONTROL-DESIGN#本轮验证与后续边界",
    "ENGINEERING-DESIGN#方法规模计量决策",
    "ENGINEERING-DESIGN#初始上限与校准边界",
    "ENGINEERING-DESIGN#统一执行与证据",
    "CONTROL-TASK#阶段验收责任决定",
    "CONTROL-TASK#产品框架代际决定",
    "CONTROL-TASK#json-外壳决定",
    "CONTROL-TASK#http-凭证传递决定",
    "CONTROL-TASK#密码算法决定",
    "CONTROL-TASK#原生注解默认决定",
    "CONTROL-TASK#flyway-启动迁移决定",
    "CONTROL-TASK#多数据源接入决定",
    "CONTROL-TASK#事务前置路由决定",
    "CONTROL-TASK#默认异常回滚决定",
    "BUILD#已确认的主技术栈",
    "BUILD#版本与兼容",
    "HTTP#已确认的-json-响应外壳",
    "HTTP#合同生成与验证",
    "ACCESS#密码与凭证保护",
    "ENGINEERING-DESIGN#其他门禁的前置事实与后续出口",
    "CONTROL-TASK#安全检查范围收缩",
    "CONTROL-TASK#基础-maven-告警验证",
    "CONTROL-TASK#依赖告警当前复验与-ci-前提",
    "CONTROL-TASK#构建类路径绑定与依赖入口确认",
    "CONTROL-TASK#应用层框架边界确认",
    "CONTROL-TASK#真实编译范围验证",
    "CONTROL-TASK#日常评审责任决定",
    "ACCESS#已确认的账号与权限变更生效原则",
    "ACCESS#已确认的密码变更与会话原则",
    "ACCESS#已确认的默认登录失败防护原则",
    "ACCESS#登录失败防护验证",
    "ACCESS#已确认的安全管理审计失败原则",
    "ACCESS#审计内容与失败验证",
    "CONTROL-TASK#执行预算收敛决定",
    "TASKS#已确认的-git-交付次序",
    "TASKS#候选提交与验收准备",
    "TASKS#已确认的验收记录原则",
    "TASKS#验收记录与交付收尾",
    "TASKS#评审发现与整改复验",
    "TASKS#流程与记录验证",
    "TASKS#已确认的验收后推进原则"
  ],
  "acceptance": null,
  "closing": null
}
```
