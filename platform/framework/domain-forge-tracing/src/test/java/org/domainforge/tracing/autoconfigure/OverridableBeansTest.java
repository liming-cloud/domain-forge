package org.domainforge.tracing.autoconfigure;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.domainforge.tracing.internal.AccessLogServletFilter;
import org.domainforge.tracing.internal.TraceIdServletFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 链路追踪的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(Sampler.class, TraceIdServletFilter.class, AccessLogServletFilter.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(TracingAutoConfiguration.class);
    }

    @Override
    protected Class<?> user() {
        return User.class;
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        Sampler userSampler() {
            throw new UnsupportedOperationException();
        }

        @Bean
        TraceIdServletFilter userTraceFilter() {
            throw new UnsupportedOperationException();
        }

        @Bean
        AccessLogServletFilter userAccessLog() {
            throw new UnsupportedOperationException();
        }
    }
}
