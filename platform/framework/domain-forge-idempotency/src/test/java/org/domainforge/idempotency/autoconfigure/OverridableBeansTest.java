package org.domainforge.idempotency.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest {
    private static final List<Class<?>> TYPES = List.of(FilterRegistrationBean.class);

    // 只解析配置类、不刷新上下文：条件注解已经求值，Bean 不会被创建，也就不需要它们的依赖。
    private static AnnotationConfigApplicationContext start(Class<?>... configurations) {
        return start(Map.of(), configurations);
    }

    private static AnnotationConfigApplicationContext start(Map<String, Object> properties,
            Class<?>... configurations) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.getBeanFactory().registerScope("session", new SimpleThreadScope());
        context.register(configurations);
        var processor = new ConfigurationClassPostProcessor();
        processor.setEnvironment(context.getEnvironment());
        processor.setResourceLoader(context);
        processor.setBeanClassLoader(context.getClassLoader());
        processor.postProcessBeanDefinitionRegistry(context);
        return context;
    }

    // 每种类型的 Bean 定义都来自指定的配置类（只检查定义，不创建 Bean）。
    private static void assertProvidedBy(
            AnnotationConfigApplicationContext context, Class<?> type, Class<?> source) {
        var names = context.getBeanFactory().getBeanNamesForType(type, true, false);
        assertTrue(names.length > 0, type + " 没有 Bean");
        for (String name : names) {
            assertEquals(
                    context.getBeanFactory().getBeanNamesForType(source, true, false)[0],
                    context.getBeanFactory().getBeanDefinition(name).getFactoryBeanName());
        }
    }

    @Test
    void userBeansReplaceDefaults() {
        var context = start(User.class, IdempotencyAutoConfiguration.class);
        TYPES.forEach(type -> assertProvidedBy(context, type, User.class));
    }

    @Test
    void defaultsApplyWithoutUserBeans() {
        var context = start(IdempotencyAutoConfiguration.class);
        TYPES.forEach(type -> assertTrue(context.getBeanFactory().getBeanNamesForType(type, true, false).length > 0,
                type.toString()));
        assertEquals(0, context.getBeanFactory().getBeanNamesForType(User.class, true, false).length);
    }

    @Test
    void switchPropertyTurnsFilterOff() {
        var context = start(Map.of("domainforge.idempotency.enabled", "false"), IdempotencyAutoConfiguration.class);
        assertEquals(0, context.getBeanFactory().getBeanNamesForType(FilterRegistrationBean.class, true, false).length);
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        FilterRegistrationBean<?> idempotencyFilter() {
            throw new UnsupportedOperationException();
        }
    }
}
