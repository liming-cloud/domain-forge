package org.domainforge.idempotency.autoconfigure;

import org.domainforge.idempotency.internal.IdempotencyAssembly;
import org.domainforge.idempotency.internal.IdempotencyFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

/**
 * 请求幂等装配：Servlet 应用中注册过滤器，排在会话与 Same-Token 等安全过滤器之后、进入 MVC 之前。
 * 记录存放在服务的会话 Redis（cache 组件的 Redisson 客户端）中。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IdempotencyAutoConfiguration {
    /**
     * 幂等过滤器；只对标了 {@code @Idempotent} 的入口生效。
     */
    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(Environment environment, RedissonClient redis,
                                                                List<RequestMappingHandlerMapping> mappings) {
        var registration = new FilterRegistrationBean<>(IdempotencyAssembly.filter(environment, redis, mappings));
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        return registration;
    }
}
