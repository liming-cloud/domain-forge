package org.domainforge.servicecall.autoconfigure;

import feign.Capability;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 四个全局 Feign Bean 都可以被业务的 Bean 替换（按名称覆盖的与默认 Bean 同名）；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(RequestInterceptor.class, ErrorDecoder.class, Capability.class, Retryer.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(ServiceCallAutoConfiguration.class);
    }

    @Override
    protected Class<?> user() {
        return User.class;
    }

    @Test
    void defaultsComeFromAutoConfiguration() {
        var context = start(Map.of(), ServiceCallAutoConfiguration.class);
        types().forEach(type -> assertProvidedBy(context, type, ServiceCallAutoConfiguration.class));
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        RequestInterceptor serviceCallIdentity() {
            throw new UnsupportedOperationException();
        }

        @Bean
        ErrorDecoder userErrors() {
            throw new UnsupportedOperationException();
        }

        @Bean
        Capability serviceCallTransport() {
            throw new UnsupportedOperationException();
        }

        @Bean
        Retryer userRetryer() {
            throw new UnsupportedOperationException();
        }
    }
}
