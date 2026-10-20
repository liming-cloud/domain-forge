package @@PACKAGE@@.bootstrap;

import org.domainforge.persistence.autoconfigure.DomainForgePersistenceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** 启动当前服务并仅扫描本上下文；不在装配入口执行业务或 SQL。 */
@SpringBootApplication(scanBasePackages = "@@PACKAGE@@")
@Import(DomainForgePersistenceConfiguration.class)
public class Application {
  /** 启动独立服务；数据库和管理端口由项目自己的运行配置提供。 */
  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
