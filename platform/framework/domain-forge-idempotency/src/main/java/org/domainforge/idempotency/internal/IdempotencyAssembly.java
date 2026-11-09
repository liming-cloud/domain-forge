package org.domainforge.idempotency.internal;

import cn.dev33.satoken.stp.StpUtil;
import org.domainforge.idempotency.api.IdempotencyProperties;
import org.redisson.api.RedissonClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

/**
 * 按配置组装幂等过滤器。
 */
public final class IdempotencyAssembly {
    private IdempotencyAssembly() {
    }

    /**
     * 保存时间、处理租期、请求体与响应体上限取自配置，默认 24 小时、60 秒、1MB。
     */
    public static IdempotencyFilter filter(String application, IdempotencyProperties properties,
            RedissonClient redis, List<RequestMappingHandlerMapping> mappings) {
        var settings = new IdempotencyFilter.Settings(application, properties.ttl(), properties.lease(),
                Math.toIntExact(properties.maxRequestSize().toBytes()),
                Math.toIntExact(properties.maxResponseSize().toBytes()));
        return new IdempotencyFilter(new IdempotentEndpoints(mappings), new IdempotencyRecords(redis),
                StpUtil::getLoginIdDefaultNull, settings);
    }
}
