package org.domainforge.access.autoconfigure;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.access.internal.RequestCurrentSubject;
import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 访问控制的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(StpLogic.class, SaTokenDao.class, RequestCurrentSubject.class, CurrentAccess.class,
                StpInterface.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(AccessControlAutoConfiguration.class);
    }

    @Override
    protected Class<?> user() {
        return User.class;
    }

    @Override
    protected Map<String, Object> properties() {
        return Map.of("domainforge.access.session-store", "redis");
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        StpLogic userStpLogic() {
            throw new UnsupportedOperationException();
        }

        @Bean
        SaTokenDao userSessionStore() {
            throw new UnsupportedOperationException();
        }

        @Bean
        RequestCurrentSubject userSubject() {
            throw new UnsupportedOperationException();
        }

        @Bean
        CurrentAccess userAccess() {
            throw new UnsupportedOperationException();
        }

        @Bean
        StpInterface userPermissions() {
            throw new UnsupportedOperationException();
        }
    }
}
