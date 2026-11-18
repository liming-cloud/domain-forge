# 请假审批

业务项目：员工提交年假申请，部门审批人审批，年假余额随之扣减。基于 Domain Forge 平台的副本建立，平台代码与文档随上游同步（[平台升级同步](docs/reference/平台升级同步.md)），业务只新增自己的服务与文档。

- 负责人：sunliming（GitHub `liming-cloud`）；问题与需求提在本仓库的 GitHub Issues
- 业务服务：[leave-service](platform/services/leave-service/README.md)；业务决定见 `docs/decisions/` 中以 `业务-` 开头的记录
- 架构与模块：[总体架构](docs/explanation/总体架构.md)
- 怎么开发、怎么提 PR：[开发流程](docs/workflow/01-开发流程.md)
- AI 编码工具入口：[AGENTS.md](AGENTS.md)
- 全部文档：[文档索引](docs/README.md)
- 本仓库如何由平台副本整理而来：[业务仓库起步清单](docs/reference/业务仓库起步清单.md)

## 目录

```text
platform/                      Maven 工程（parent 与聚合入口：platform/pom.xml）
  domain-forge-bom/            平台组件的版本管理
  domain-forge-build-rules/    统一架构规则与 Checkstyle 配置，所有模块自动执行
  shared/                      纯共享类型
  framework/                   13 个通用组件：web-support、persistence、cache、access-control、config、
                               service-call、messaging、scheduling、tracing、file-storage、notification、idempotency、defaults
  services/                    平台服务：iam（身份权限）、gateway（API 网关）；业务服务 leave-service（请假审批）
  templates/ddd-service/       业务服务生成模板
deploy/                        Docker Compose 部署参考
docs/                          规则、参考、解释、决定与流程文档（业务决定以 业务- 开头）
scripts/                       文档链接检查、单服务冒烟，以及模板、部署与闭环的验证脚本
```

## 构建与验证

需要 JDK 25 及以上。集成测试需要 Docker。

```bash
# 完整门禁：编译、架构规则、单元测试、集成测试、格式（Spotless）、Checkstyle、依赖声明
platform/mvnw -f platform/pom.xml verify

# 本机没有 Docker 时跳过集成测试（CI 会完整执行）
platform/mvnw -f platform/pom.xml verify -DskipITs -Ddocker.skip=true

# 整理格式：格式以 Spotless 的输出为准，可对整个仓库执行
platform/mvnw -f platform/pom.xml spotless:apply

# 文档链接检查
python3 scripts/check_doc_links.py
```

CI（`.github/workflows/ci.yml`）在每个 PR 上执行 `mvn verify`，并验证服务模板、部署参考、模板服务接入 IAM 的闭环和文档链接。`mvn verify` 具体检查哪些内容，见[工程与构建](docs/reference/工程与构建.md#质量门禁)。
