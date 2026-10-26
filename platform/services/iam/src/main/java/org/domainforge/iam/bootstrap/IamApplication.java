package org.domainforge.iam.bootstrap;

import org.domainforge.iam.interfaces.cli.AdministratorCommand;
import org.domainforge.iam.interfaces.cli.LoginPolicyCommand;
import org.domainforge.persistence.autoconfigure.DomainForgePersistenceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

/**
 * 每项目一个IAM上下文部署，服务实现不作为其他上下文运行依赖。
 */
@SpringBootApplication(scanBasePackages = "org.domainforge.iam")
@Import(DomainForgePersistenceConfiguration.class)
public class IamApplication {
    /**
     * 启动需要明确数据库、Redis及安全配置，不提供默认凭据。
     */
    public static void main(String[] args) {
        if (args.length > 0 && "initialize-admin".equals(args[0])) {
            AdministratorCommand.run(
                    java.util.Arrays.copyOfRange(args, 1, args.length), IamApplication::maintenanceContext);
            return;
        }
        if (args.length > 0 && "login-policy".equals(args[0])) {
            LoginPolicyCommand.run(
                    java.util.Arrays.copyOfRange(args, 1, args.length), IamApplication::maintenanceContext);
            return;
        }
        SpringApplication.run(IamApplication.class, args);
    }

    /**
     * 本地维护只启动必要非Web上下文，业务HTTP和调度保持关闭。
     */
    private static org.springframework.context.ConfigurableApplicationContext maintenanceContext() {
        return new SpringApplicationBuilder(IamApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--spring.main.web-application-type=none");
    }
}
