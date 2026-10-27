package org.domainforge.config.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.config.api.DynamicSettingsBinder;
import org.domainforge.config.internal.EnvironmentSettingsBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * 提供动态配置绑定，并在启动时确认配置确实来自声明的配置中心。
 */
@AutoConfiguration
public class ConfigConfiguration {
    static final String EXPECTED_SOURCE = "domainforge.config.expected-source";
    static final String SOURCE = "domainforge.config.source";

    /**
     * 声明了期望来源（如 nacos）时，配置中必须带有同值的来源标记；Nacos 对不存在的 dataId 返回空内容而不报错，
     * 不检查就会以本地默认值启动。
     */
    @Bean
    DynamicSettingsBinder domainForgeDynamicSettings(Environment environment, ObjectProvider<MeterRegistry> metrics) {
        String expected = environment.getProperty(EXPECTED_SOURCE);
        if (expected != null && !expected.equals(environment.getProperty(SOURCE))) {
            throw new IllegalStateException(
                    "配置未从 " + expected + " 读取：缺少来源标记 " + SOURCE + "，检查 dataId 是否已发布");
        }
        return new EnvironmentSettingsBinder(environment, metrics.getIfAvailable(), Clock.systemUTC());
    }
}
