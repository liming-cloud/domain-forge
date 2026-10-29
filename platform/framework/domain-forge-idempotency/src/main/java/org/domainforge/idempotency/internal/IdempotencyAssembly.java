package org.domainforge.idempotency.internal;

import cn.dev33.satoken.stp.StpUtil;
import org.redisson.api.RedissonClient;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;
import java.util.List;

/**
 * 按配置组装幂等过滤器。
 */
public final class IdempotencyAssembly {
    private static final String PREFIX = "domainforge.idempotency.";

    private IdempotencyAssembly() {
    }

    /**
     * 保存 24 小时、处理租期 60 秒、请求体与响应体上限 1MB，均可配置。
     */
    public static IdempotencyFilter filter(Environment environment, RedissonClient redis,
                                           List<RequestMappingHandlerMapping> mappings) {
        var settings = new IdempotencyFilter.Settings(
                environment.getProperty("spring.application.name", "app"),
                environment.getProperty(PREFIX + "ttl", Duration.class, Duration.ofHours(24)),
                environment.getProperty(PREFIX + "lease", Duration.class, Duration.ofSeconds(60)),
                size(environment, "max-request-size"),
                size(environment, "max-response-size"));
        return new IdempotencyFilter(new IdempotentEndpoints(mappings), new IdempotencyRecords(redis),
                StpUtil::getLoginIdDefaultNull, settings);
    }

    private static int size(Environment environment, String name) {
        return Math.toIntExact(DataSize.parse(environment.getProperty(PREFIX + name, "1MB")).toBytes());
    }
}
