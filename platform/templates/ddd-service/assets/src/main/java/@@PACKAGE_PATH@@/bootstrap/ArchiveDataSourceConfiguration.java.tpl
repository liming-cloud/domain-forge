package @@PACKAGE@@.bootstrap;

import org.apache.ibatis.session.SqlSessionFactory;
import org.domainforge.persistence.api.AdditionalDataSource;
import org.domainforge.persistence.api.AdditionalMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 附加数据源 archive（便笺归档库，独立数据库）：迁移在 db/archive，Mapper 标 {@link AdditionalMapper}、XML 在 mapper-archive。
 *
 * <p>事务管理器与会话工厂不是默认候选，主数据源的自动装配不受影响；用例以
 * {@code @Transactional("archiveTransactionManager")} 选择归档库的事务。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "@@PACKAGE@@.infrastructure.archive", annotationClass = AdditionalMapper.class,
        sqlSessionFactoryRef = "archiveSqlSessionFactory")
public class ArchiveDataSourceConfiguration {
    /**
     * 连接池、迁移与会话；连接配置见 archive-datasource.yml。
     */
    @Bean(destroyMethod = "close")
    AdditionalDataSource archive(Environment environment) {
        return AdditionalDataSource.create("archive", environment);
    }

    /**
     * 归档库的本地事务。
     */
    @Bean(defaultCandidate = false)
    PlatformTransactionManager archiveTransactionManager(AdditionalDataSource archive) {
        return archive.transactionManager();
    }

    /**
     * 归档库的 MyBatis 会话工厂。
     */
    @Bean(defaultCandidate = false)
    SqlSessionFactory archiveSqlSessionFactory(AdditionalDataSource archive) {
        return archive.sqlSessionFactory();
    }
}
