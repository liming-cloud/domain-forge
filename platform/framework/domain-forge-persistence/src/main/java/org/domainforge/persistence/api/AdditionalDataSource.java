package org.domainforge.persistence.api;

import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * 服务的附加数据源（与主数据源不同的独立数据库）：连接池、版本化迁移、本地事务管理器与 MyBatis 会话工厂各自独立。
 *
 * <p>配置 {@code domainforge.datasources.<名称>.url／username／password}（凭据只来自环境变量）与可选的
 * {@code maximum-pool-size}（默认 5）；迁移目录 {@code classpath:db/<名称>}，Mapper XML 在
 * {@code classpath*:mapper-<名称>/**}{@code /*.xml}（与主数据源的 {@code mapper/} 目录分开）。由
 * {@code AdditionalDataSources.create} 创建，创建时即执行迁移，失败则启动失败。
 *
 * <p>服务在 bootstrap 中把事务管理器与会话工厂声明为 {@code @Bean(defaultCandidate = false)}，主数据源的自动装配不受影响；
 * 用例以 {@code @Transactional("<名称>TransactionManager")} 选择事务。不提供跨数据源事务（S-12），
 * 在一个数据源的事务里访问另一个数据源的 Mapper 会被拒绝。
 */
public interface AdditionalDataSource extends AutoCloseable {
    /**
     * 数据源名称。
     */
    String name();

    /**
     * 连接池。
     */
    DataSource dataSource();

    /**
     * 本数据源的本地事务管理器（默认超时同 {@code spring.transaction.default-timeout}）。
     */
    DataSourceTransactionManager transactionManager();

    /**
     * 本数据源的 MyBatis 会话工厂（含分页与跨数据源误用检查）。
     */
    SqlSessionFactory sqlSessionFactory();

    /**
     * 关闭连接池。
     */
    @Override
    void close();
}
