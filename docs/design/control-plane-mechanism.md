# 文档控制面机制

正文、身份／路由、任务事实、执行结果分别只有一个维护来源。自动核对不证明 Agent 主观阅读、全部业务语义或人工批准真实性。实际前沿见[唯一任务记录](../tasks/control-plane-verification.md#唯一任务记录)。

## 调用方与不变量

平台及消费项目研发者、本地与 CI 共用 Python 标准库入口。Python 3.14.5 为已核对本地环境，不决定产品技术版本。治理工具和隔离夹具不是平台组件或业务工程。

## 方案比较与选择

采用单一[目录配置](../../tools/control_plane/catalog.json)：保存文档 ID、分类、触发、小节及验证责任，不复制规则、阈值或任务状态。总索引、分类索引、活动与归档入口由配置及任务事实生成，不手工维护并行目录。

人工阅读顺序用 `reading_order` 登记，必须恰好覆盖全部有效规则／入口文档，不重复、不包含任务或历史材料。编号只表示顺序，ID 保持稳定；首次人工通读不扩大 Agent 每次任务的最低清单。设计及任务从分类入口按需补读。

规则配置以 `reference_inputs` 登记路径和引用字段，实际 ID／小节仍从原 JSON 读取；缺文件／字段、重复字段、越界或无效引用拒绝。该检查不复制数值或产生批准。

## 最低清单与实际变更

- 任务类型、计划范围、实际 Git 差异、目标、设计及语义补充共同生成最低小节清单，去重并保留命中理由。`extra_refs` 只能补充，不能删减路由责任。
- 实际范围包含固定起点后的已提交、暂存、未暂存和非忽略未跟踪文件；删除和重命名前后路径均核对。
- 文件用途和触发由受检查的分类登记识别，冲突、未知或缺失路由明确失败，不产生空清单通过。生产用途不能靠目录名、降低可见性或自报 test 取得豁免。
- `scope_reasons` 集中保存实际文件与计划理由，各小节引用它，避免按文件×规则重复全文。
- Java／模块／注册接口等实际范围由对应工程机制核对，文档分类和四类读取试验不证明业务行为。

## 任务唯一记录与入口

每个任务只有一个 `control-plane-task` JSON 块。目标、交付单元、消费者、范围、起点、设计、验证计划、前沿、待办、延期、验收及收尾均由它维护；普通任务引用有效基线，不复制规范。

未验收任务保持活动；真实验收后通过归档定位，未完成收尾及待处理事项仍可见。当前指针只指向一个有效登记任务。来源、精确功能 SHA 与收尾分别核对；索引不产生状态，记录不预写自身未来提交或 CI 结果。有效设计、未解决问题不随归档隐藏；失效材料按[文档生命周期](../workflow/document-control.md#已确认的文档生命周期原则)处理。

## 执行与证据

| 机制 | 确定规则 |
|---|---|
| 当前／产品责任 | 默认 `stage: current`。仅真实产品结果为 product，须有非空唯一当前 `mechanism_checks` 和延期原因；未知阶段、无效／循环／自引用关联拒绝 |
| 阶段派生 | 仅文档／治理任务且全部实际用途为文档／治理时为 control-plane；任何实际生产／测试变化即为 product，不接受外传宽松阶段 |
| 产品延期 | control-plane 阶段选中产品结果时明确 not_executed、executed:false、原因和机制关联；产品阶段恢复必需性。当前机制失败／缺环境仍阻断 |
| 校准适用性 | `maintenance` 只登记输入模式及已有当前自动反例检查。不能把结构、人工、场景或产品责任重标为校准，不能删除已登记工程反例以关闭 CI |
| 普通文档变化 | 仅增任务／设计／索引身份和阅读顺序不触发全量原生校准；规则身份、路由、分类、检查、执行代码、统一规范、门禁设计或 CI 变化仍校准 |
| 比较依据 | 与精确起点配置作结构比较，仅判差异，不恢复旧规则；起点配置缺失／不可解析及全零事件起点保守要求完整校准 |
| 未执行责任 | 不适用项列入 `not_applicable_checks` 并保留理由和 executed:false；延期项单列。显式要求的当前候选校准不被适用性裁掉 |
| 真实执行 | 只运行目录配置固定命令及必要 `environment_argv`，不执行任务自带任意命令。输入、配置、工具和环境前后核对，变化失效 |
| 普通评审 | `semantic-review` 接收 Agent 或研发评审者的当前输入记录；核对命中规则、真实材料摘要、结论及未解决发现。其他人工责任不能重标为普通评审 |
| 结果 | 保存实际命令、退出码、诊断及范围；空测试、跳过、失败、未实现或缺环境不得报通过。语义及人工责任不由执行器生成 |

报告写指定临时输出目录，不进入维护输入，不为每次运行另作进度提交。对象／覆盖核对不提供签名或防恶意伪造；正式交付仍需实际执行及适用可信 CI。

## 公开命令与记录字段

在仓库根执行，公开命令输出 JSON 和准确退出码：

```sh
python3 -B tools/control_plane/control_plane.py check
python3 -B tools/control_plane/control_plane.py index --write
python3 -B tools/control_plane/control_plane.py route --task control-plane-verification
python3 -B tools/control_plane/control_plane.py context --task control-plane-verification
python3 -B tools/control_plane/control_plane.py context --task control-plane-verification --ref 'DOCS#任务上下文与增长控制'
python3 -B -m unittest discover -s tools/control_plane -p 'test_*.py'
python3 -B tools/control_plane/control_plane.py run --task control-plane-verification --review /绝对路径/review.json
```

| 命令 | 输出与边界 |
|---|---|
| check | 核对分类覆盖、身份、小节、链接、替代关系、索引与任务事实；不是内容准确性或产品通过 |
| index [--write] | 默认输出派生视图；write 机械更新登记索引，不覆盖普通正文。修改配置或任务事实后重生成并 check |
| route --task ID | 返回最低小节、命中理由、阶段、required／maintenance／deferred／not_applicable_checks；不产生遵规结果 |
| context --task ID [--ref ID#小节] | 默认仅摘要、最低目录及全部验证责任。ref 可重复且只能选当前清单；返回精确正文和行号，重叠父子范围合并；loaded_refs 仅本次输出，remaining_refs 显式列其余，部分阅读不缩小验证责任 |
| ci-plan --base SHA | 使用事件精确差异，不复用长期任务起点。全零表示首次／手动完整校准；未知用途失败，真实产品 CI 未接入时以 CP_CI_PRODUCT_UNWIRED 拒绝，不只跑文档放绿 |
| run --task ID [--review /绝对路径/review.json] [--delivery-query /绝对路径/query.json] | 实际运行全部适用已实现自动检查并核对普通评审；交付对象选择交给 delivery；缺记录、未实现、场景缺证、失败及环境不足保留非零，不产生人工验收 |
| delivery --task ID [--query /绝对路径/query.json] | 只读核对精确提交、GitHub 远端和 Actions job／step；缺少查询对象仍非零，校准通过不替代真实对象；支持边界见下节 |
| verify-receipt --task ID --receipt /绝对路径/receipt.json | 核对当前输入、环境、阶段、分类及完整执行／延期责任；不重跑、不防伪、不替代正式验证，旧输入或省略责任拒绝 |

context 同时包含目标和设计引用；不会默认附加历史正文，也不累积跨次主观阅读状态。结构检查内部读取材料不等于全部内容进入 Agent 上下文。固定输入在读取前后核对，旧摘要不是新实现的遵规证明。

任务字段：`goals/unit/design_refs/extra_refs` 为真实 ID＋小节；`base` 为精确 Git 起点；type／scopes 为登记触发；verification 为检查 ID＋当前通过依据；frontier／pending／deferred 分别是前沿、待办和后续结果。无真实验收时 acceptance 为 null，closing 独立保存收尾事实。

普通评审记录放在仓库外，字段为 `task/input/actor/coverage/findings/pending_decisions`。`input` 使用当前 context 的字节摘要，actor 为 agent 或 human。coverage 按评审主题分组，每组保存非空 `refs/evidence/conclusion`；refs 恰好覆盖 route 中命中 semantic-review 的规则，不复制规则正文。evidence 使用材料路径与 `sha256`：仓库文件必须为当前维护输入的相对路径，外部场景报告用绝对路径。findings 和 pending_decisions 保存未解决项，有内容则不通过；整改处置留于任务事实。输入、记录或材料执行期间变化则失效。

评审结果的 `proof_scope` 为 `review-record-integrity-only`。入口不读取材料语义、不判断结论是否正确、不证明评审者身份；Agent 必须实际评审，不能只生成记录。重大人工决定与完整单元验收仍按[任务责任](../workflow/task-execution.md#责任与人工决定)办理，所有执行报告 `human_acceptance` 均为 false。

## 交付证据具体适配

[具体适配器](../../tools/control_plane/delivery_evidence.py)落实[候选准备](../workflow/task-execution.md#候选提交与验收准备)及[记录收尾](../workflow/task-execution.md#验收记录与交付收尾)的可判定部分。当前只支持仓库已使用的 `github.com` origin 和 `.github/workflows/control-plane.yml` 的单轴矩阵结构；新增托管方式、事件或条件语法须适配，不默认放行。Git／CI 对象核对、来源真实性、人工验收和操作授权仍是不同事实。

公开入口：

```sh
python3 -B tools/control_plane/control_plane.py delivery --task control-plane-verification --query /绝对路径/query.json
python3 -B tools/control_plane/control_plane.py run --task control-plane-verification --review /绝对路径/review.json --delivery-query /绝对路径/query.json
```

仓库外查询文件只选择 `task`、`functional_sha`、`branch`、`functional_run_id`；功能 SHA 必须为真实完整提交，run ID 为真实正整数。验收后记录收尾另加 `record_run_id`；记录 SHA 从当前 HEAD 读取，不要求提交自引用。查询不接受命令、替代 API 地址、通过声明或复制的 CI 报告。此文件是临时对象选择，不是第二份任务／验收状态源。当前无真实对象时不编造示例 SHA／run ID 填入任务。

- 本地 Git：核对任务起点祖先、功能对象、干净的精确提交树及实际文件字节／模式；隐藏修改、未提交输入、错误 SHA、链接或子模块等未支持类型拒绝。清除继承的 Git 路由和替换对象配置，读取不更新索引、引用或工作区。
- 远端与 CI：使用 `gh api --hostname github.com --method GET`，凭据由本机已有 GitHub CLI 认证环境提供，不读取或输出凭据。核对远端分支精确对象、同仓库／分支／SHA／工作流，以及 `push` 或 `workflow_dispatch` 运行的最新 attempt。PR 合并对象等事件当前不支持。对象依据来自 [GitHub workflow runs API](https://docs.github.com/en/rest/actions/workflow-runs) 和 [workflow jobs API](https://docs.github.com/en/rest/actions/workflow-jobs)。
- 执行覆盖：必需 job 和命名 step 从固定提交中的当前工作流读取；功能相对任务起点涉及校准输入时要求完整校准，手动完整校准也要求所有组。记录提交只改验收资料时允许校准 job 按既有依赖跳过；必需 job／step 缺失、空执行、失败、跳过、重复或分页不全均拒绝。读取末尾重查 run、job、远端和本地对象；总绿灯不能替代这些对象。单次原生读取上限 30 秒，统一 run 仍使用 180 秒单检查上限；超时是证据不可用，不伪造产品失败或成功。
- 验收记录例外：记录必须是获验收功能对象的一个直接、单父 `doc:` 中文提交；只允许唯一任务 JSON 中 acceptance／closing／frontier／pending 字段和准确派生索引变化。代码、合同、规范、任务范围、非派生索引、额外记录提交及模式变化均拒绝。真实用户来源和功能 SHA 仍保留于任务；收尾 CI 失败时不抹去已有验收记录。
- 人工边界：适配器核对既有来源引用与字节关联，返回 `source-association-only`，不验证身份、消息真实性或验收语义。`ok` 仅表示可判定 Git／CI 对象检查通过，报告始终为 `human_acceptance:false`、`delivery_complete:false` 并保留 `human_review:required`。它不是批准来源，不能独立满足整套验收或下一单元推进条件。真实人类确认仍须按任务流程审阅，Agent 自填 actor、链接或协议夹具均不能替代。

[本地反例](../../tools/control_plane/test_delivery_evidence.py)使用真实临时 Git 对象和明确标记的 GitHub 协议模拟；通过测试专用 Python seam 注入响应，生产 CLI 不开放注入选项。既有 `control-plane-tests` 和 CI 的发现命令执行这些反例，不增加新的 Java 校准组。它们证明适配行为，不证明本仓库已提交推送、真实远端 CI 已运行或人工已验收。真实查询缺证据时 `delivery-evidence` 继续返回失败，整体 run 不因本地校准变绿。

## 本轮验证与后续边界

[公开回归](../../tools/control_plane/test_control_plane.py)覆盖合法轻量任务、四类入口、合并去重、实际差异扩大、未知／冲突、漏索引／规则、无效章节／目标／指针、旧输入、空执行、跳过、来源缺失、失败收尾、阅读包及人工顺序。反例必须命中目标诊断，整改后通过；临时 Git 操作限隔离目录，不修改项目 .git。

真实工程及剩余机制按[工程设计](engineering-gates.md#其他门禁的前置事实与后续出口)推进，未完成责任由目录及任务保留。本地机制通过不冒充真实产品、远端 CI、独立人工评审或整套验收。
