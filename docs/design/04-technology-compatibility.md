# 技术选型、官方证据与兼容矩阵

状态：待讨论设计 / 待实测。资料核实日期：2026-09-19 至 2026-09-20；版本快照取自 2026-09-19 的官方页面。技术负责人：用户；资料整理：AI。运行工具存在不等于选型接受，官方支持说明不等于整套组合已经通过测试。

## 1. 首批候选与证据

下表记录本次设计时的版本快照，不要求未来研发继续使用过期补丁。进入验证切片时重新核实稳定版，在接受的版本线内形成精确清单；跨版本线变化需要评审。

| 能力 | 候选 | 官方资料能够支持的事实 | 项目结论 / 后续证据 |
|---|---|---|---|
| Spring Boot | 4.1.1 | 官方项目页当前列出该版；系统要求支持 Java 17–26 | 候选，T-V01 待实测；[项目页](https://spring.io/projects/spring-boot/) / [系统要求](https://docs.spring.io/spring-boot/system-requirements.html) |
| Spring Cloud | 2025.1.3 | 2025.1 系列从 2025.1.2 起兼容 Boot 4.1 | BOM 组合有官方依据，T-V01 待实测；[兼容表](https://spring.io/projects/spring-cloud/) |
| Spring Cloud Alibaba | 2025.1.0.0 | 稳定版适配表基于 Boot 4.0.0、Cloud 2025.1.0；对应 nacos-client 3.1.1 | 不据此宣称支持本项目 Boot 4.1 组合，T-V02；[版本说明](https://sca.aliyun.com/docs/2025.x/overview/version-explain/) |
| Nacos Server | 3.2.4 | 发布页列出稳定发布；服务器版本与 Alibaba 的客户端版本是不同对象 | 3.2.4 server + 3.1.1 client 是待验证配对，T-V02；[发布记录](https://github.com/alibaba/nacos/releases) |
| Sa-Token | 1.46.0，Boot 4 集成包 | 官方发布记录列出 Boot 4 适配及该版本修复 | 会话/序列化/撤销适配仍需 T-V03；[发布记录](https://github.com/dromara/Sa-Token/releases) |
| MyBatis-Plus | 3.5.17，Boot 4 starter | 安装文档提供专用 starter；插件依赖需按需引入 | SQL、事务和分页等组合待 T-V04；[安装文档](https://baomidou.com/getting-started/install/) |
| 数据权限 | MyBatis-Plus DataPermissionInterceptor | 使用 SQL 解析和自定义处理器添加范围条件 | 不保证自动覆盖全部读写；T-V05 按支持清单验证；[插件说明](https://baomidou.com/plugins/data-permission/) |
| Java / 构建 | Java 21 + Maven Wrapper（推荐）；Java 25 为备选 | Java 21/25 均在 Boot 文档支持区间 | R2 确认；本机 Java 21 的存在只是开发便利性证据；T-V01 |
| HTTP / 网关 | Spring MVC、Gateway Server Web MVC、HTTP Service Clients + LoadBalancer | 官方存在 Gateway MVC 和 HTTP Service Client 的负载均衡接入 | 不推断 Sa-Token 过滤器对所有路由自动生效；T-V06；[Gateway](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/starter.html) / [LoadBalancer](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/loadbalancer.html) |
| MySQL / 迁移 | MySQL 8.4 LTS + Flyway（推荐） | MySQL 有维护中的 8.4 文档及 LTS 说明 | 非“所有组件均最新”的承诺；驱动、Flyway/MySQL 模块与镜像补丁在 T-V04 冻结；[MySQL 文档](https://dev.mysql.com/doc/refman/8.4/en/) |
| 缓存 | Redis + Spring Cache；会话与通用缓存隔离配置 | Spring 提供 Redis 缓存集成 | Redis 镜像、客户端和序列化候选由验证清单锁定；不加入二级缓存；[Spring Boot 缓存](https://docs.spring.io/spring-boot/reference/io/caching.html) |
| API 文档 | springdoc-openapi 3.1.1 候选 | 官方 v3 页面提供 Boot 4 集成信息 | 具体 Boot 4.1 组合待 T-V07；[springdoc](https://springdoc.org/) |
| 口令存储 | Argon2id 候选 | OWASP 推荐采用现代自适应口令哈希，优先 Argon2id | 参数、依赖和资源消耗在 T-V03 冻结，不自研加密；[OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) |

不关闭 Spring Cloud 兼容检查来掩盖失败，不手工覆盖大量传递依赖来拼出未经维护的组合。若官方依据冲突，以最小失败证据形成选择题；用户确认调整后更新 ADR 和矩阵。

## 2. 后续能力及接入边界

| 能力 | 建议技术方向 | 拥有者和边界 | 未来验证重点 |
|---|---|---|---|
| S3 | AWS SDK for Java v2，显式兼容端点 | 业务决定对象可见性、key 归属和授权；组件处理协议访问 | 上传/读取/删除、预签名到期、错误与重试；S3 兼容不等于所有供应商特性一致；[AWS SDK](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html) |
| Kafka | Spring Kafka | 业务定义事件语义，组件承载传输和消费约定 | 重复消息、幂等、重试/死信、顺序范围；涉及 DB+消息时设计 Outbox；[Spring Kafka](https://docs.spring.io/spring-kafka/reference/) |
| Elasticsearch | Spring Data Elasticsearch + 对应服务端 | 业务持有查询模型和访问边界，ES 是派生索引 | 官方版本配对、过滤/分页、重建与版本切换、同步失败；[版本矩阵](https://docs.spring.io/spring-data/elasticsearch/reference/elasticsearch/versions.html) |

上述方向不是首版依赖，不在当前阶段锁定未使用的补丁版本。每项能力开始设计时刷新官方资料、确认实际用例；不会为了串联技术栈索引账号密码或把权限正确性依赖于异步搜索索引。

## 3. 未来兼容验证任务（全部未执行）

| ID | 需要验证的事实 | 最小方法 | 通过 / 失败处置 |
|---|---|---|---|
| T-V01 | JDK/Boot/Cloud 与 BOM 可组合 | 研发获准后创建最小受控应用，解析依赖并启动 | 无依赖冲突与兼容检查失败；失败保留依赖树和日志 |
| T-V02 | Nacos 注册、发现、配置加载/刷新、鉴权 | 对候选 server/client 配对运行一次完整调用及重启/失连用例 | 正常路径和故障路径满足契约；只读配置导入策略也需验证 |
| T-V03 | Sa-Token 会话、序列化、凭据哈希、版本撤销 | 真实 Redis 与数据库，测试 T01–T09 | 明确失效窗口、签发/续期并发、重启行为及参数；不能只靠内存会话测试 |
| T-V04 | MySQL、MP、Flyway 的事务与 schema 演进 | 从空库迁移、重复启动、事务回滚及升级 | 无自动建表隐式变更；迁移版本可追溯 |
| T-V05 | 数据权限受支持 SQL 清单 | 基于真实 MySQL 对照有权与无权夹具 | D01–D15 通过；不支持形态拒绝，不能回退无过滤查询 |
| T-V06 | 网关、发现、HTTP 客户端、身份传播 | 经网关及直连消费服务分别验收；注入伪造身份头 | 不能绕过下游授权，调用超时与不可用可区分 |
| T-V07 | OpenAPI 与公共协议一致 | 校验生成的文档与契约断言、错误和分页示例 | 文档与实际 JSON 一致，管理文档暴露有配置控制 |
| T-V08 | 发布制品可被独立项目消费 | 隔离工作目录、干净依赖缓存、只读消费凭据 | 不借助平台源码/本机 install，不泄露发布凭据 |

## 4. 配置、数据迁移与版本策略候选

- 使用 Boot/Cloud BOM 管理传递依赖，项目 BOM 只统一未被合适上游管理的必要版本。明确 BOM 优先级并检查最终依赖树；不使用动态版本或 SNAPSHOT 作为发行基线。
- 使用 Nacos 的配置导入方式前，核实当前版本支持；Alibaba 2025.1.0.0 的发布说明已移除旧 bootstrap 配置支持。[官方发布说明](https://github.com/alibaba/spring-cloud-alibaba/releases/tag/2025.1.0.0)
- 启动配置、业务可刷新参数和秘密分开。配置变化有类型校验、审计与失败保持策略，安全规则不允许通过热配置静默放宽。
- MySQL schema 采用版本化迁移；已发布迁移不可修改，兼容变更先扩展再收缩。回退应用前检查 schema 兼容性，不承诺破坏性迁移自动回滚。
- starter 配置项和公共契约纳入语义化版本；首次正式版本前也记录破坏性变更。升级说明列明旧/新版本、必要数据操作、配置变化及回退边界。
- 通用缓存与会话通过键空间、过期策略和连接权限区分；具体 Redis 实例拓扑取决于环境。数据缓存故障可在应用明确允许时读权威数据，认证故障不能套用同样降级。

## 5. GitHub 能力事实与待核实条件

| 事实 | 设计影响 | 官方来源 |
|---|---|---|
| PR 作者不能审批自己的 PR | 当前同账号流程采用人工审阅/手动合并，不设置无法满足的自审批要求 | [Required reviews](https://docs.github.com/en/pull-requests/how-tos/review-pull-requests/approving-a-pull-request-with-required-reviews) |
| 私有仓库分支保护可用性受套餐影响 | R3 必须检查实际支持与生效状态，不能把文件存在等同于强制门禁 | [Protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches) |
| GitHub Maven 包消费需要认证，跨仓库权限需另核实 | 凭据与权限是制品消费验收前置；不默认一个仓库的 GITHUB_TOKEN 可读取另一个私有仓库 | [Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry) |

实际 owner、仓库是否存在、套餐、读写凭据、发布权限仍未确认。本阶段不登录 CLI、不创建远程资源、不配置秘密。
