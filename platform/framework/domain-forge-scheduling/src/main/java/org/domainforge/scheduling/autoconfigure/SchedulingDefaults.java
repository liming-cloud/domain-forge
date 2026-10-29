package org.domainforge.scheduling.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 把平台的调度配置映射为 PowerJob worker 的配置（最低优先级，服务可覆盖）。
 *
 * <p>worker 只在 {@code domainforge.scheduling.enabled=true} 且为 Web 应用时启动：服务的维护命令（非 Web 上下文）
 * 即使带着部署环境的配置，也不会接收调度。只用 HTTP 通信、内存存储，工作目录放在 /tmp（容器中的服务用户没有主目录）。
 */
public final class SchedulingDefaults implements EnvironmentPostProcessor, Ordered {
    /**
     * 按开关与应用类型决定是否启用 worker。
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean enabled = environment.getProperty("domainforge.scheduling.enabled", Boolean.class, false)
                && application.getWebApplicationType() != WebApplicationType.NONE;
        if (enabled && System.getProperty("powerjob.worker.workspace") == null)
            System.setProperty("powerjob.worker.workspace", "/tmp/powerjob-worker");
        environment.getPropertySources().addLast(new MapPropertySource("domain-forge-scheduling-defaults", Map.of(
                "powerjob.worker.enabled", Boolean.toString(enabled),
                "powerjob.worker.app-name", "${domainforge.scheduling.app-name:${spring.application.name}}",
                "powerjob.worker.server-address", "${domainforge.scheduling.server-address:}",
                "powerjob.worker.protocol", "http",
                "powerjob.worker.port", "${domainforge.scheduling.worker-port:27777}",
                "powerjob.worker.store-strategy", "memory",
                "powerjob.worker.allow-lazy-connect-server", "false")));
    }

    /**
     * 在应用配置加载之后追加，不覆盖服务配置。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
