# 持久化组件参考

`domain-forge-persistence` 的技术选型与用法。规则见[持久化规范](../../rules/05-持久化.md)与[事务与一致性](../../rules/02-系统设计.md#事务与一致性)，产品范围见[产品定义](../../product/01-产品定义.md#关系持久化与多数据源边界)。

## 技术选型

| 项 | 决定 |
|---|---|
| ORM | MyBatis-Plus。不因框架提供通用 Service、Active Record 或代码生成器就采用其分层 |
| 数据库 | PostgreSQL。其他数据库不因 MyBatis-Plus 支持就算平台支持，新增须完整验证 |
| 迁移 | Flyway，应用启动时自动执行版本化 SQL（2026-10-13 决定），应用进程持有迁移凭据 |
| 多数据源 | 持久化组件的 `AdditionalDataSource`，每个附加数据源有独立的迁移、事务与会话（[#36](https://github.com/liming-cloud/domain-forge/issues/36)）；不用动态数据源路由 |
| 事务回滚 | 全异常回滚，包含受检异常；需要异常后提交的场景显式声明（2026-10-13 决定） |

## 附加数据源

需要第二个独立数据库时，用 `AdditionalDataSource`：

- 每个附加数据源有自己的名称与配置组 `domainforge.datasources.<名称>.url／username／password`（凭据只来自环境变量），自己的迁移目录 `db/<名称>`、Mapper XML 目录 `mapper-<名称>/`、事务管理器与会话工厂；Mapper 标 `@AdditionalMapper`，以 `@MapperScan(annotationClass = AdditionalMapper.class, sqlSessionFactoryRef = "<名称>SqlSessionFactory")` 扫描各自的包，主数据源按 `@Mapper` 扫描，互不收录。
- 在 bootstrap 中把事务管理器与会话工厂声明为 `@Bean(defaultCandidate = false)`：否则 Spring Boot 会认为服务自己提供了数据源资源，主数据源的自动装配随之退让。
- 用例以 `@Transactional("<名称>TransactionManager")` 选择事务。一个事务里访问另一个数据源的 Mapper 时，组件在执行 SQL 前拒绝（双向），不会静默地以自动提交执行。
- 附加数据库的备份恢复见[部署参考](../../../deploy/README.md#备份与恢复)；它不在主库的健康检查中。
