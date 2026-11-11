package org.domainforge.iam.infrastructure;

import org.domainforge.iam.bootstrap.IamApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 集成测试共享的 IAM 应用：启动参数相同的 IT 在同一 JVM 中只启动一次上下文。
 *
 * <p>测试之间的数据隔离由各 IT 每个测试前清空业务表与 Redis 保证。关闭交给 Spring 注册的 JVM 关闭钩子；
 * 需要自建上下文的 IT 先调用 {@link #close()}：Sa-Token 用静态单例保存当前存储，两个上下文同时存在会互相覆盖。
 */
final class SharedIamApplication {
    private static ConfigurableApplicationContext application;

    private SharedIamApplication() {
    }

    // 外部环境提供临时数据库，缺失输入直接失败，不能跳过后显示绿灯。
    static synchronized ConfigurableApplicationContext get() {
        if (application == null || !application.isActive()) {
            assertNotNull(System.getenv("IAM_DB_URL"), "需要真实数据库验证环境");
            application = new SpringApplicationBuilder(IamApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.main.banner-mode=off", "--logging.level.root=WARN");
        }
        return application;
    }

    static synchronized void close() {
        if (application != null) application.close();
        application = null;
    }
}
