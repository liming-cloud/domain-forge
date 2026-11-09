# 0024 与 rag-pilot 统一 issue 和 PR 约定

- 状态：已接受（2026-11-09）
- 关联 issue：待建（见 PR 描述）

## 背景

domain-forge 和 rag-pilot 都由 AI 编码工具主导研发，两边的 issue 模板、PR 模板和提交前缀各写一套（任务 / 结果、`bug:` / `fix:`、`doc:` / `docs:`），人和 AI 在两个仓库之间切换时要记两套约定。

## 决定

- 两个仓库各放一份内容相同的 `.github/ISSUE_TEMPLATE/`（结果、缺陷、不稳定测试）、`.github/pull_request_template.md` 和 `scripts/check_pr_guard.sh`，修改时同步另一个仓库。不建组织级 `.github` 仓库：本地 AI 要能直接读到模板。
- 提交信息和 PR 标题前缀统一为 `feat` / `fix` / `build` / `docs`（Conventional Commits 的标准词），历史提交不改。
- 主 issue 类型统一为“结果”（`kind/result`），字段取两边之长：目标、已确认的决定、未决问题、验收标准、改动范围（含不做的）、开工、实施记录。杂务不单设类型。`kind/slice` 改名为 `kind/result`，`kind/chore`、`kind/capability` 作为历史标签保留。
- issue 标题不加前缀，用标签分类。

## 后果

- 项目特有的内容（验证命令、评测、截图）不写进模板，由 AGENTS.md 和开发流程承载。
- 业务仓库按[起步清单](../reference/业务仓库起步清单.md)改写模板，不受同步约束。
