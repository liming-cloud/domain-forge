package org.domainforge.access.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessDefaultsTest {
    // 服务覆盖单项后，其余会话策略仍取组件默认值。
    @Test
    void serviceOverrideKeepsUnchangedDefaults() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("service", Map.of("sa-token.timeout", "3600")));
        new AccessDefaults().postProcessEnvironment(environment, null);
        assertEquals("3600", environment.getProperty("sa-token.timeout"));
        assertEquals("Authorization", environment.getProperty("sa-token.token-name"));
        assertEquals("false", environment.getProperty("sa-token.is-read-cookie"));
        assertEquals("redis", environment.getProperty("domainforge.access.session-store"));
    }
}
