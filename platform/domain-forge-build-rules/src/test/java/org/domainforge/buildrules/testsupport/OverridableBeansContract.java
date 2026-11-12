package org.domainforge.buildrules.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * “Bean 可覆盖”的公共测试（S-25、O-03）：组件的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 * <p>
 * 组件的 {@code OverridableBeansTest} 继承本类，只写本组件特有的部分：要检查的类型、组件的配置类、条件需要的配置项， 以及一个名为
 * {@code User} 的嵌套配置类（每种类型提供一个业务 Bean；按名称覆盖的与默认 Bean 同名）。
 */
public abstract class OverridableBeansContract {

    /** 要检查的公开 Bean 类型。 */
    protected abstract List<Class<?>> types();

    /** 组件的配置类，以及条件需要的辅助配置类；按注册顺序排列。 */
    protected abstract List<Class<?>> configurations();

    /** 业务提供 Bean 的配置类；只检查定义，不会被创建。 */
    protected abstract Class<?> user();

    /** 条件注解需要的配置项。 */
    protected Map<String, Object> properties() {
        return Map.of();
    }

    @Test
    void userBeansReplaceDefaults() {
        var configurations = new ArrayList<Class<?>>();
        configurations.add(user());
        configurations.addAll(configurations());
        var context = start(properties(), configurations.toArray(Class<?>[]::new));
        types().forEach(type -> assertProvidedBy(context, type, user()));
    }

    @Test
    void defaultsApplyWithoutUserBeans() {
        var context = start(properties(), configurations().toArray(Class<?>[]::new));
        types().forEach(type -> assertTrue(context.getBeanFactory().getBeanNamesForType(type, true, false).length > 0,
                type.toString()));
        assertEquals(0, context.getBeanFactory().getBeanNamesForType(user(), true, false).length);
    }

    // 只解析配置类、不刷新上下文：条件注解已经求值，Bean 不会被创建，也就不需要它们的依赖。
    protected static AnnotationConfigApplicationContext start(Map<String, Object> properties,
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
    protected static void assertProvidedBy(AnnotationConfigApplicationContext context, Class<?> type,
            Class<?> source) {
        var names = context.getBeanFactory().getBeanNamesForType(type, true, false);
        assertTrue(names.length > 0, type + " 没有 Bean");
        for (String name : names) {
            assertEquals(context.getBeanFactory().getBeanNamesForType(source, true, false)[0],
                    context.getBeanFactory().getBeanDefinition(name).getFactoryBeanName());
        }
    }
}
