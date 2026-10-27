package org.domainforge.gateway.infrastructure.routing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 部署环境变量 GATEWAY_* 按宽松绑定映射到 gateway.*；配置组放在 gateway.routing 下，不能把它们当作未知键。
 */
class EnvironmentBindingTest {
    @Test
    void deploymentVariablesDoNotLeakIntoRoutingGroup() {
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("env", Map.of(
                "GATEWAY_REDIS_HOST", "redis",
                "GATEWAY_TRUSTED_PROXIES", "172.31.250.10",
                "GATEWAY_NACOS_SERVER_ADDR", "nacos:8848")));
        sources.addLast(new MapPropertySource("yaml", Map.of(
                "gateway.routing.rate-limit.replenish-per-second", 20,
                "gateway.routing.rate-limit.burst", 40,
                "gateway.routing.routes[0].id", "iam",
                "gateway.routing.routes[0].service", "iam",
                "gateway.routing.routes[0].paths[0]", "/api/**")));
        var settings = new Binder(ConfigurationPropertySources.get(environment))
                .bind("gateway.routing", Bindable.of(GatewaySettings.class),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                .get();
        assertEquals("iam", settings.routes().getFirst().id());
    }
}
