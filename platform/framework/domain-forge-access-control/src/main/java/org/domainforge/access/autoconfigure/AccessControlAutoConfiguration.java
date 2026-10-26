package org.domainforge.access.autoconfigure;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.access.internal.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 原生 Sa-Token 会话与访问执行的默认装配；会话策略来自 access-defaults.yml，可由服务 YAML 覆盖。
 *
 * <p>会话存储使用 Sa-Token 官方 Redisson DAO，与 cache 组件共用同一个 RedissonClient。
 */
@AutoConfiguration
public class AccessControlAutoConfiguration {
    /**
     * 唯一登录类型；应用会话生命周期与入口校验使用同一原生逻辑。
     */
    @Bean
    @ConditionalOnMissingBean
    public StpLogic accessStpLogic() {
        return new StpLogic("login");
    }

    /**
     * 官方 Redisson DAO；测试可以把 domainforge.access.session-store 设为 memory 使用原生内存实现。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "domainforge.access.session-store", havingValue = "redis")
    public SaTokenDao accessSessionStore(RedissonClient redisson) {
        return new SaTokenDaoForRedisson(redisson);
    }

    /**
     * Servlet 应用的访问执行链：遗漏声明默认拒绝，完整主体由应用提供的事实源给出。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Import(AccessFailureAdvice.class)
    static class WebAccess {
        /**
         * 每个应用必须提供唯一事实源，缺失时启动失败。
         */
        @Bean
        RequestCurrentSubject requestCurrentSubject(CurrentSubjectSource source) {
            return new RequestCurrentSubject(source);
        }

        /**
         * 当前主体只从原生实际读取的令牌建立。
         */
        @Bean
        CurrentAccess currentAccess(RequestCurrentSubject subjects, StpLogic logic) {
            return () -> subjects.current(logic.getTokenValue());
        }

        /**
         * 原生权限与角色数据只来自本请求已验证的主体。
         */
        @Bean
        StpInterface accessStpInterface(CurrentAccess current, StpLogic logic) {
            return new CurrentSubjectPermissions(current, logic);
        }

        @Bean
        AccessDeclarations accessDeclarations() {
            return new AccessDeclarations();
        }

        @Bean
        AccessPolicyValidator accessPolicyValidator(
                ObjectProvider<RequestMappingHandlerMapping> mappings, AccessDeclarations declarations) {
            return new AccessPolicyValidator(mappings, declarations);
        }

        /**
         * 先拒绝遗漏声明并验证完整主体，再交原生执行登录、权限、角色及 AND／OR。
         *
         * <p>当前主体延迟取得：事实源常依赖 HTTP 客户端（如 Feign），而客户端装配又会读取 MVC 配置，立即注入会成环。
         */
        @Bean
        WebMvcConfigurer accessInterceptors(
                AccessDeclarations declarations, ObjectProvider<CurrentAccess> currentAccess, StpLogic logic) {
            CurrentAccess current = () -> currentAccess.getObject().current();
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new AccessDeclarationInterceptor(declarations, current, logic))
                            .order(-100)
                            .addPathPatterns("/**");
                    registry.addInterceptor(new NativeAccessInterceptor()).order(-99).addPathPatterns("/**");
                }
            };
        }
    }
}
