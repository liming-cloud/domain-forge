package @@PACKAGE@@.bootstrap;

import org.domainforge.persistence.autoconfigure.DomainForgePersistenceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * 启动本服务：只扫描本上下文根包；IAM 客户端只在 infrastructure/acl 中声明。
 */
@SpringBootApplication(scanBasePackages = "@@PACKAGE@@")
@EnableFeignClients(basePackages = "@@PACKAGE@@.infrastructure.acl")
@Import(DomainForgePersistenceConfiguration.class)
public class @@APP_CLASS@@ {
    /**
     * 数据库、Redis 与 IAM 地址由部署配置提供，不内置凭据。
     */
    public static void main(String[] args) {
        SpringApplication.run(@@APP_CLASS@@.class, args);
    }
}
