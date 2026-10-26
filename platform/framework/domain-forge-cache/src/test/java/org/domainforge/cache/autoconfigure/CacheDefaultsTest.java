package org.domainforge.cache.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheDefaultsTest {
    @Test
    void hostOverrideKeepsUnchangedDefaults() {
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("host", Map.of("spring.data.redis.port", "6380")));
        new CacheDefaults().postProcessEnvironment(environment, null);
        assertEquals("6380", environment.getProperty("spring.data.redis.port"));
        assertEquals("localhost", environment.getProperty("spring.data.redis.host"));
        assertEquals("0", environment.getProperty("domainforge.cache.retry-attempts"));
    }
}
