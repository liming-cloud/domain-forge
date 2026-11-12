package org.domainforge.files.autoconfigure;

import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.ObjectStorage;
import org.domainforge.files.internal.FileCleanup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 文件存储的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(ObjectStorage.class, FileStore.class, FileCleanup.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(FileStorageAutoConfiguration.class);
    }

    @Override
    protected Class<?> user() {
        return User.class;
    }

    @Override
    protected Map<String, Object> properties() {
        return Map.of("domainforge.files.enabled", "true");
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        ObjectStorage userStorage() {
            throw new UnsupportedOperationException();
        }

        @Bean
        FileStore userStore() {
            throw new UnsupportedOperationException();
        }

        @Bean
        FileCleanup userCleanup() {
            throw new UnsupportedOperationException();
        }
    }
}
