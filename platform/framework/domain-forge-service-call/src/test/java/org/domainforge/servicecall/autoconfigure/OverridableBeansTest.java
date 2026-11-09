package org.domainforge.servicecall.autoconfigure;

import feign.Capability;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四个全局 Feign Bean 都可以被业务的 Bean 替换（按名称覆盖的与默认 Bean 同名）；业务不提供时回归默认实现。
 */
class OverridableBeansTest {
    private static final List<Class<?>> TYPES =
            List.of(RequestInterceptor.class, ErrorDecoder.class, Capability.class, Retryer.class);

    private static AnnotationConfigApplicationContext start(Class<?>... configurations) {
        var context = new AnnotationConfigApplicationContext();
        context.register(configurations);
        context.addBeanFactoryPostProcessor(
                factory -> {
                    for (String name : factory.getBeanDefinitionNames()) {
                        factory.getBeanDefinition(name).setLazyInit(true);
                    }
                });
        context.refresh();
        return context;
    }

    // 每种类型的 Bean 定义都来自指定的配置类。
    private static void assertProvidedBy(
            AnnotationConfigApplicationContext context, Class<?> type, Class<?> source) {
        var names = context.getBeanNamesForType(type, true, false);
        assertTrue(names.length > 0, type + " 没有 Bean");
        for (String name : names) {
            assertEquals(
                    context.getBeanNamesForType(source, true, false)[0],
                    context.getBeanFactory().getBeanDefinition(name).getFactoryBeanName());
        }
    }

    @Test
    void userBeansReplaceDefaults() {
        try (var context = start(User.class, ServiceCallAutoConfiguration.class)) {
            TYPES.forEach(type -> assertProvidedBy(context, type, User.class));
        }
    }

    @Test
    void defaultsApplyWithoutUserBeans() {
        try (var context = start(ServiceCallAutoConfiguration.class)) {
            TYPES.forEach(type -> assertProvidedBy(context, type, ServiceCallAutoConfiguration.class));
        }
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
