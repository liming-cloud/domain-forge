package org.domainforge.web.autoconfigure;

import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.domainforge.web.internal.MultipartExceptionAdvice;
import org.domainforge.web.internal.PlatformExceptionAdvice;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Web 支持的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(PlatformExceptionAdvice.class, MultipartExceptionAdvice.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(WebSupportAutoConfiguration.class);
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
        PlatformExceptionAdvice userAdvice() {
            throw new UnsupportedOperationException();
        }

        @Bean
        MultipartExceptionAdvice userMultipartAdvice() {
            throw new UnsupportedOperationException();
        }
    }
}
