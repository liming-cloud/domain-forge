package org.domainforge.buildrules;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;

import java.util.List;
import java.util.Map;

/**
 * 自动配置规则：{@code autoconfigure} 包中的 {@code @Bean} 方法必须带 {@code @ConditionalOnMissingBean}，
 * 业务才能用自己的 Bean 替换默认实现；确实不可替换的 Bean 在 {@link #NOT_OVERRIDABLE} 中登记理由。
 */
final class AutoConfigurationRules {
    private static final String BEAN = "org.springframework.context.annotation.Bean";
    private static final String ON_MISSING_BEAN =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean";

    /** 键为“类简名#方法名”（嵌套类用外层类名.内层类名），值为不可替换的理由。 */
    private static final Map<String, String> NOT_OVERRIDABLE =
            Map.ofEntries(
                    Map.entry("AccessControlAutoConfiguration.WebAccess#accessPolicyValidator", "启动期访问策略校验，替换等于关闭安全检查"),
                    Map.entry("AccessControlAutoConfiguration.WebAccess#accessInterceptors", "强制执行访问声明，替换等于绕过授权"),
                    Map.entry("AccessControlAutoConfiguration.WebAccess#accessDeclarations", "访问声明注册表，校验与拦截共用同一个实例"),
                    Map.entry("AccessControlAutoConfiguration.WebAccess#sameTokenFilter",
                            "由 domainforge.access.same-token.enabled 开关决定"),
                    Map.entry("AccessControlAutoConfiguration.WebAccess#sameTokenDisabled",
                            "由 domainforge.access.same-token.enabled 开关决定的启动期检查"),
                    Map.entry("CacheConfiguration#domainForgeRedisson", "Redisson 定制器，本就允许多个并存，不是被替换的对象"),
                    Map.entry("PersistenceAutoConfiguration#domainForgePersistenceResources",
                            "启动期校验事务、会话与迁移绑定同一数据源"),
                    Map.entry("SchedulingAutoConfiguration#schedulingDisabled", "未启用调度时的启动期检查"),
                    Map.entry("WebSupportAutoConfiguration#adviceConflicts", "启动期校验错误处理器冲突"));

    private AutoConfigurationRules() {
    }

    static void check(JavaClasses classes, List<String> violations) {
        for (JavaClass type : classes) {
            if (!inAutoconfigurePackage(type)) {
                continue;
            }
            for (JavaMethod method : type.getMethods()) {
                if (method.isAnnotatedWith(BEAN)
                        && !method.isAnnotatedWith(ON_MISSING_BEAN)
                        && !NOT_OVERRIDABLE.containsKey(key(method))) {
                    violations.add(
                            "[Bean 可覆盖] "
                                    + type.getName()
                                    + "."
                                    + method.getName()
                                    + "：autoconfigure 中的 @Bean 须带 @ConditionalOnMissingBean，"
                                    + "确实不可替换的在 AutoConfigurationRules.NOT_OVERRIDABLE 登记理由");
                }
            }
        }
    }

    private static boolean inAutoconfigurePackage(JavaClass type) {
        return ("." + type.getPackageName() + ".").contains(".autoconfigure.");
    }

    private static String key(JavaMethod method) {
        String owner = method.getOwner().getName();
        String simple = owner.substring(owner.lastIndexOf('.') + 1).replace('$', '.');
        return simple + "#" + method.getName();
    }
}
