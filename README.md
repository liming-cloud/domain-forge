# Domain Forge

公司级 DDD 微服务脚手架：可复用的基础组件、身份权限（IAM）后端、业务服务模板和部署参考方案，以及让 AI 编码工具与人工评审都能遵守的研发规范和自动检查。业务服务在本仓库内以源码方式复用平台能力。

- 产品目标与范围：[产品定义](docs/product/01-产品定义.md)
- 当前进度与首版 MVP：[路线图](docs/product/02-路线图.md)
- 架构与模块：[总体架构](docs/architecture/01-总体架构.md)
- 怎么开发、怎么提 PR：[开发流程](docs/workflow/01-开发流程.md)
- AI 编码工具入口：[AGENTS.md](AGENTS.md)
- 全部文档：[文档索引](docs/README.md)

## 目录

```text
platform/                      Maven 工程（parent 与聚合入口：platform/pom.xml）
  domain-forge-build-rules/    统一架构规则与 Checkstyle 配置，所有模块自动执行
  shared/                      纯共享类型
  framework/                   通用组件：web-support、persistence、cache、access-control
  services/iam/                身份权限服务
  templates/ddd-service/       业务服务生成模板
  verification/                端到端与冒烟验证
deploy/                        Docker Compose 部署参考
docs/                          产品、架构、规范与流程文档
scripts/                       仓库辅助脚本（文档链接检查）
```

## 构建与验证

需要 JDK 21 及以上。集成测试需要 Docker。

```bash
# 完整门禁：编译、架构规则、单元测试、集成测试、格式、Checkstyle、依赖声明
platform/mvnw -f platform/pom.xml verify

# 本机没有 Docker 时跳过集成测试（CI 会完整执行）
platform/mvnw -f platform/pom.xml verify -DskipITs -Ddocker.skip=true

# 格式以 .editorconfig（IntelliJ 风格）为准；没有 IntelliJ 时只整理自己改动的文件
platform/mvnw -f platform/pom.xml spotless:apply -DspotlessFiles='.*/MyClass.java'

# 文档链接检查
python3 scripts/check_doc_links.py
```

CI（`.github/workflows/ci.yml`）在每个 PR 上执行同样的 `mvn verify` 和文档链接检查。`mvn verify` 具体检查哪些内容，见[工程与构建](docs/architecture/02-工程与构建.md#质量门禁)。
