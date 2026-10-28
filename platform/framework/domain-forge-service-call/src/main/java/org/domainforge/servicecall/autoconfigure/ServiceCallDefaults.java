package org.domainforge.servicecall.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

/**
 * 只加载本组件固定 YAML，作为服务配置可覆盖的最低优先级默认值。
 */
public final class ServiceCallDefaults implements EnvironmentPostProcessor, Ordered {
    /**
     * 使用 Boot 原生解析，缺失组件资源属于打包错误。
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            var resource = new ClassPathResource("META-INF/domain-forge/service-call-defaults.yml");
            for (var source : new YamlPropertySourceLoader().load("domain-forge-service-call-defaults", resource)) {
                environment.getPropertySources().addLast(source);
            }
        } catch (IOException error) {
            throw new IllegalStateException("跨服务调用组件默认配置无法加载", error);
        }
    }

    /**
     * 在应用配置加载之后追加，不覆盖环境、profile 或启动参数。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
