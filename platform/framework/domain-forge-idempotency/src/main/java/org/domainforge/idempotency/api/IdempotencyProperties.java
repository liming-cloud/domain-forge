package org.domainforge.idempotency.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 幂等配置（前缀 {@code domainforge.idempotency}）。
 *
 * @param enabled         设为 false 时不注册过滤器
 * @param ttl             记录保存时间
 * @param lease           处理租期，超过后允许重试
 * @param maxRequestSize  参与指纹计算的请求体上限
 * @param maxResponseSize 可缓存的响应体上限
 */
@ConfigurationProperties("domainforge.idempotency")
public record IdempotencyProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("1MB") DataSize maxRequestSize,
        @DefaultValue("1MB") DataSize maxResponseSize) {
}
