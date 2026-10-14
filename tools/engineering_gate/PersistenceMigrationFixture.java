package probe.adapter;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** 两个资源独立启动迁移，顺序明确；不宣称跨资源原子性。 */
@Configuration(proxyBeanMethods = false)
public class PersistenceMigrationFixture {
  /** 主资源只扫描自己声明的迁移位置。 */
  @Bean
  public Flyway masterFlyway(DynamicRoutingDataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource.getDataSource("master"))
        .locations("classpath:db/master")
        .cleanDisabled(true)
        .load();
  }

  /** 主资源由应用的原生初始化器执行迁移。 */
  @Bean
  public FlywayMigrationInitializer masterMigration(@Qualifier("masterFlyway") Flyway flyway) {
    return new FlywayMigrationInitializer(flyway);
  }

  /** 第二个资源使用自己的连接与独立迁移位置。 */
  @Bean
  public Flyway inventoryFlyway(DynamicRoutingDataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource.getDataSource("inventory"))
        .locations("classpath:db/inventory")
        .cleanDisabled(true)
        .load();
  }

  /** 主资源完成后启动第二个资源；后者失败不会回滚前者的提交。 */
  @Bean
  @DependsOn("masterMigration")
  public FlywayMigrationInitializer inventoryMigration(
      @Qualifier("inventoryFlyway") Flyway flyway) {
    return new FlywayMigrationInitializer(flyway);
  }
}
