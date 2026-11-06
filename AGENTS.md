# AGENTS.md：AI 编码工具工作入口

本仓库是公司级 DDD 微服务脚手架。你写的代码会被 `mvn verify` 自动检查，再由人工通过 PR 评审。工程规则以[规则卡](docs/rules/00-索引.md)为准，本页只做路由。

## 开工前

0. 任务来自 GitHub「首版」里程碑中 `status/ready`、且依赖（blocked by）已全部关闭的任务 issue，编号小的优先（流程见[开发流程](docs/workflow/01-开发流程.md#任务与-issue)）。issue 描述是唯一依据。没有就绪的任务时，先把未决问题写进对应 issue，不凭路线图自行开工。
1. 读[产品定义](docs/product/01-产品定义.md)中与任务相关的小节（任务与验收场景的对应见[路线图](docs/product/02-路线图.md#首版跟踪)），确认任务在范围内。
2. 读[总体架构](docs/explanation/总体架构.md)：模块划分、依赖方向、DDD 分层和查询短路径。
3. 按任务类型读规则卡和参考页（卡号连续的一段写成范围，只读相关的卡；参考页只读用到的组件）：

| 任务涉及 | 规则卡 | 参考 |
|---|---|---|
| 写 Java 代码（任何改动） | [S-01、S-06](docs/rules/02-系统设计.md#职责与依赖)，[C-01–C-16](docs/rules/04-源码质量.md) | |
| 新增或修改业务用例、领域模型 | [W-01–W-04](docs/workflow/01-开发流程.md#需求与设计)，[R-05–R-14](docs/rules/01-需求与领域建模.md)，[S-07–S-15](docs/rules/02-系统设计.md) | |
| SQL、Mapper、迁移 | [P-01–P-18](docs/rules/05-持久化.md) | [持久化组件](docs/reference/components/persistence.md) |
| 事务、一致性、集成事件 | [S-11、S-12、S-15、S-20–S-23](docs/rules/02-系统设计.md#事务与一致性) | [消息与 Outbox](docs/reference/components/messaging.md) |
| HTTP 接口、错误码、失败处理 | [H-01–H-10](docs/rules/06-接口与错误.md)，[A-01、A-02](docs/rules/07-安全与访问控制.md#访问策略) | [Web 组件](docs/reference/components/web-support.md)，[错误码](docs/reference/错误码.md) |
| 登录、权限、凭据、审计 | [A-01–A-16](docs/rules/07-安全与访问控制.md) | [身份权限参考](docs/reference/身份权限.md)（改模型时读[身份权限模型](docs/explanation/身份权限模型.md)） |
| 配置、启动、部署 | [O-01–O-10](docs/rules/08-配置与运行.md) | [部署参考](deploy/README.md)，[配置中心](docs/reference/components/config.md) |
| 重试、日志、指标、链路 | [F-03–F-09](docs/rules/09-故障与可观测性.md)，[A-08](docs/rules/07-安全与访问控制.md#a-08-凭据和敏感内容不进入日志响应审计和报告) | [观测数据](docs/reference/观测数据.md) |
| 平台组件、扩展点 | [S-06、S-17、S-24、S-25](docs/rules/02-系统设计.md)，[O-03](docs/rules/08-配置与运行.md#o-03-spring-装配方式)，[W-05](docs/workflow/01-开发流程.md#w-05-组件设计回答五个问题) | [扩展路径](docs/reference/扩展路径.md)，`docs/reference/components/<组件>.md` |
| 新增依赖、构建、插件 | [B-01–B-10](docs/rules/03-技术与构建.md)，[W-08–W-10](docs/workflow/01-开发流程.md#依赖与升级) | [工程与构建](docs/reference/工程与构建.md) |
| 测试 | [T-01–T-13](docs/rules/10-测试与验证.md) | [验证索引](docs/reference/验证索引.md) |

决定的来龙去脉在[决定记录](docs/decisions/README.md)，不必为开工而读。

## 命令

```bash
platform/mvnw -f platform/pom.xml verify                                  # 完整门禁（约 25 分钟）
platform/mvnw -f platform/pom.xml verify -DskipITs -Ddocker.skip=true     # 无 Docker 时
platform/mvnw -f platform/pom.xml spotless:apply                         # 整理格式（结果确定，可对整个仓库执行）
python3 scripts/check_doc_links.py                                        # 文档链接
```

提交前 `mvn verify` 必须通过；检查不通过时修正对象，不放宽检查（[W-11](docs/workflow/01-开发流程.md#w-11-发现问题时修正对象不放宽检查)）。开发中先缩小范围（`<模块>` 写相对 `platform/` 的路径，如 `services/iam`），提交前再跑完整门禁；单测试类、单服务冒烟等命令与注意事项见[工程与构建](docs/reference/工程与构建.md#快速反馈)：

```bash
M="platform/mvnw -f platform/pom.xml"
$M -pl <模块> -am verify -DskipITs -Ddocker.skip=true   # 单模块门禁：单元测试与静态检查，不起容器
scripts/smoke_service.sh <服务>                          # 单服务冒烟，约 1 分钟
```

## 会被自动拦下的硬规则

正文见各卡；规则的实现在 `platform/domain-forge-build-rules/`，漏判或误判时修改规则并补正反例，不要绕过。

- [S-01](docs/rules/02-系统设计.md#s-01-五个职责各管各的事) 五个职责包与依赖方向；[S-02](docs/rules/02-系统设计.md#s-02-领域层不依赖框架)、[S-03](docs/rules/02-系统设计.md#s-03-应用层只用必要的框架元数据) 领域层与应用层可用的类型。
- [S-06](docs/rules/02-系统设计.md#s-06-用包划分边界跨边界只经公开合同) 跨上下文只引用 `application.contract`，不访问其他组件的 `internal` 包。
- [P-03](docs/rules/05-持久化.md#p-03-sql-写在-mapper-xml) SQL 只写在 Mapper XML 或合法的 MyBatis-Plus 链式条件里。
- [C-09](docs/rules/04-源码质量.md#c-09-每个生产类型都有中文说明) 每个生产类型有中文说明；[C-13](docs/rules/04-源码质量.md#c-13-方法复杂度上限) 圈复杂度 ≤ 10，可执行语句 ≤ 30。
- [B-05](docs/rules/03-技术与构建.md#b-05-直接使用的依赖必须显式声明) 直接用到的依赖显式声明；[C-03](docs/rules/04-源码质量.md#c-03-统一代码格式) 格式以 Spotless 的输出为准。

## 需要先停下来问人的事

见 [W-07](docs/workflow/01-开发流程.md#w-07-分清事实建议和需要人决定的事)：范围与合同兼容性、安全语义、新依赖、删除或放宽检查。

## 交付

按[开发流程](docs/workflow/01-开发流程.md#pr-要求)开 PR：一个 PR 解决一个任务 issue，描述写 `Closes #n` 与闭合的验收场景；没做完的部分建后续 issue，由人同意；行为变化要有测试，文档在同一 PR 中更新。
