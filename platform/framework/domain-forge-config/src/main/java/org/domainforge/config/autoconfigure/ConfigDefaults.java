package org.domainforge.config.autoconfigure;

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
public final class ConfigDefaults implements EnvironmentPostProcessor, Ordered {
    /**
     * 使用 Boot 原生解析，缺失组件资源属于打包错误。
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            var resource = new ClassPathResource("META-INF/domain-forge/config-defaults.yml");
            for (var source : new YamlPropertySourceLoader().load("domain-forge-config-defaults", resource)) {
                environment.getPropertySources().addLast(source);
            }
        } catch (IOException error) {
            throw new IllegalStateException("配置组件默认配置无法加载", error);
        }
    }

    /**
     * 须在 Nacos 的导入检查（紧随配置数据加载）之前执行；以 addLast 追加，仍是最低优先级，不覆盖服务配置。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
