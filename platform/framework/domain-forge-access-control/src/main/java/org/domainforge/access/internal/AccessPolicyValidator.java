package org.domainforge.access.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 启动时校验全部业务入口的访问声明，非法声明让应用启动失败，而不是等到首次请求。
 */
public final class AccessPolicyValidator implements SmartInitializingSingleton {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final AccessDeclarations declarations;

    /**
     * 处理器映射由 Spring MVC 原生装配提供。
     */
    public AccessPolicyValidator(
            ObjectProvider<RequestMappingHandlerMapping> mappings, AccessDeclarations declarations) {
        this.mappings = mappings;
        this.declarations = declarations;
    }

    @Override
    public void afterSingletonsInstantiated() {
        mappings.orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream())
                .filter(method -> !AccessDeclarations.framework(method))
                .forEach(declarations::policy);
    }
}
