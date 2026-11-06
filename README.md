# Domain Forge

公司级 DDD 微服务脚手架：可复用的基础组件、身份权限（IAM）后端、API 网关、业务服务模板和部署参考方案，以及让 AI 编码工具与人工评审都能遵守的研发规范和自动检查。业务服务在本仓库内以源码方式复用平台能力。

- 架构与模块：[总体架构](docs/explanation/总体架构.md)
- 怎么开发、怎么提 PR：[开发流程](docs/workflow/01-开发流程.md)
- AI 编码工具入口：[AGENTS.md](AGENTS.md)
- 全部文档：[文档索引](docs/README.md)
- 用本仓库开始一个业务项目：[业务仓库起步清单](docs/reference/业务仓库起步清单.md)；跟进平台升级：[平台升级同步](docs/reference/平台升级同步.md)
- 平台维护者：产品定义、路线图与平台研发流程在 `docs/platform/`（业务项目已删除）

## 目录

```text
platform/                      Maven 工程（parent 与聚合入口：platform/pom.xml）
  domain-forge-bom/            平台组件的版本管理
  domain-forge-build-rules/    统一架构规则与 Checkstyle 配置，所有模块自动执行
  shared/                      纯共享类型
  framework/                   12 个通用组件：web-support、persistence、cache、access-control、config、
                               service-call、messaging、scheduling、tracing、file-storage、notification、idempotency
  services/                    平台服务：iam（身份权限）、gateway（API 网关）；业务服务也放在这里
  templates/ddd-service/       业务服务生成模板
deploy/                        Docker Compose 部署参考
docs/                          规则、参考、解释、决定与流程文档（platform/ 为平台维护者专用）
scripts/                       文档链接检查、单服务冒烟，以及模板、部署与闭环的验证脚本
```

## 构建与验证

需要 JDK 21 及以上。集成测试需要 Docker。

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
