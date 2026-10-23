# AGENTS.md：AI 编码工具工作入口

本仓库是公司级 DDD 微服务脚手架。你写的代码会被 `mvn verify` 自动检查，再由人工通过 PR 评审。先读本页，再按任务类型读对应规范。

## 开工前

1. 读[产品定义](docs/product/01-产品定义.md)中与任务相关的小节，以及[路线图](docs/product/02-路线图.md)中的当前阶段，确认任务在范围内。
2. 读[总体架构](docs/architecture/01-总体架构.md)：模块划分、依赖方向、DDD 分层和查询短路径。
3. 按任务类型读规范（每份都是规则卡，可以只读相关小节）：

| 任务涉及 | 必读 |
|---|---|
| 新增或修改业务用例、领域模型 | [需求与领域建模](docs/standards/01-需求与领域建模.md)、[系统设计](docs/standards/02-系统设计.md) |
| 写 Java 代码（任何改动） | [源码质量](docs/standards/04-源码质量.md) |
| SQL、Mapper、迁移、事务 | [持久化](docs/standards/05-持久化.md) |
| HTTP 接口、错误码 | [接口与错误](docs/standards/06-接口与错误.md)、[Web 组件](docs/architecture/03-Web组件.md) |
| 登录、权限、凭据、审计 | [安全与访问控制](docs/standards/07-安全与访问控制.md)、[身份权限设计](docs/architecture/04-身份权限.md) |
| 配置、启动、部署 | [配置与运行](docs/standards/08-配置与运行.md) |
| 异常、日志、指标 | [故障与可观测性](docs/standards/09-故障与可观测性.md) |
| 新增依赖、构建、插件 | [技术与构建](docs/standards/03-技术与构建.md)、[工程与构建](docs/architecture/02-工程与构建.md) |
| 测试 | [测试与验证](docs/standards/10-测试与验证.md) |

## 命令

```bash
platform/mvnw -f platform/pom.xml verify                                  # 完整门禁
platform/mvnw -f platform/pom.xml verify -DskipITs -Ddocker.skip=true     # 无 Docker 时
platform/mvnw -f platform/pom.xml spotless:apply                          # 整理格式
python3 scripts/check_doc_links.py                                        # 文档链接
```

提交前 `mvn verify` 必须通过。不要为了通过而关闭检查、加例外或删除测试；确实需要例外时，在 PR 里说明理由，由人工决定。

## 会被自动拦下的硬规则

- 业务服务的类必须位于 `bootstrap`、`interfaces`、`application`、`domain`、`infrastructure` 五个职责包之一。
- 依赖方向：`domain` 只依赖自身和 JDK、`shared`；`application` 不依赖 `infrastructure`、`interfaces`；任何层都不依赖 `bootstrap`。
- `application` 只能使用 JDK、`shared`、组件的 `api` 包和少量 Spring 装配/事务注解；`interfaces` 不直接使用 SQL、Mapper、Redis 客户端。
- 不访问其他组件的 `internal` 包；跨上下文只引用对方的 `application.contract`。
- SQL 只写在 Mapper XML 或合法的 MyBatis-Plus 链式条件里；禁止 `@Select` 等 SQL 注解、`apply/last` 等原始 SQL 拼接，以及 Java 中的 SQL 字符串。
- 生产代码的每个类型都要有中文说明；方法圈复杂度不超过 10，可执行语句不超过 30。
- 代码直接用到的依赖必须在 POM 中显式声明；格式遵循 google-java-format。

规则的实现在 `platform/domain-forge-build-rules/`。规则漏判或误判时修改规则并补正反例，不要绕过。

## 需要先停下来问人的事

- 改变产品范围、公开接口合同或数据库结构的兼容性。
- 安全语义：认证、授权、会话失效、审计、凭据处理。
- 引入新的框架、中间件或基础依赖。
- 删除或放宽已有测试、规则、校验。

## 交付

- 一个 PR 做一件事，描述按 PR 模板写清：做了什么、为什么、怎么验证、有什么风险。
- 行为变化要有测试；接口、配置、设计变化要同步更新对应文档（每条规则只在一处维护，其他地方链接过去）。
- 不创建空模块、空分层目录或没有用途的接口；按真实用例创建代码。
