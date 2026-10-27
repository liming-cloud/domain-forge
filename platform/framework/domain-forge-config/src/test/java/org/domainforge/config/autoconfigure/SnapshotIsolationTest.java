package org.domainforge.config.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nacos 配置客户端只在 config-center profile 启用时开启，其余情况保持组件默认的关闭。
 */
class SnapshotIsolationTest {
    private static String enabledWith(Map<String, Object> properties) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new SnapshotIsolation().postProcessEnvironment(environment, new SpringApplication());
        new ConfigDefaults().postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty("spring.cloud.nacos.config.enabled");
    }

    @Test
    void enablesClientOnlyForConfigCenterProfile() {
        assertEquals("true", enabledWith(Map.of("spring.profiles.active", "prod, config-center")));
        assertEquals("false", enabledWith(Map.of("spring.profiles.active", "prod")));
        assertEquals("false", enabledWith(Map.of()));
    }

    @Test
    void explicitSettingStillWins() {
        assertEquals("false", enabledWith(Map.of(
                "spring.profiles.active", "config-center", "spring.cloud.nacos.config.enabled", "false")));
    }
}
