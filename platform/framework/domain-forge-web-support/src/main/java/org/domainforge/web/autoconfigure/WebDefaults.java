package org.domainforge.web.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Web 组件的最低优先级默认值：默认日志格式输出请求标识，服务配置可以覆盖。
 */
public final class WebDefaults implements EnvironmentPostProcessor, Ordered {
    /**
     * 无请求上下文的日志（启动、定时任务）输出占位符“-”。
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(
                "domain-forge-web-defaults", Map.of("logging.pattern.correlation", "[%X{requestId:--}] ")));
    }

    /**
     * 在应用配置加载之后追加，不覆盖环境、profile 或启动参数。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
