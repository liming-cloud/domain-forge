package org.domainforge.buildrules;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaPackage;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 全仓约定：日志只用 slf4j；每个生产包由 {@code package-info.java} 标注 JSpecify {@code @NullMarked}，未标
 * {@code @Nullable} 的类型都不为空。
 */
final class ConventionRules {
    static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";

    private static final List<String> FORBIDDEN_LOGGING = List.of("java.util.logging", "org.apache.commons.logging");

    private ConventionRules() {
    }

    static void check(JavaClasses classes, List<String> violations) {
        Set<String> unmarked = new TreeSet<>();
        for (JavaClass type : classes) {
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                checkLogging(type, dependency.getTargetClass(), violations);
            }
            JavaPackage owner = type.getPackage();
            if (!owner.isAnnotatedWith(NULL_MARKED)) {
                unmarked.add(owner.getName());
            }
        }
        unmarked.forEach(name -> violations.add(
                "[空值约定] 包 " + name + " 缺少 package-info.java 上的 @NullMarked"));
    }

    private static void checkLogging(JavaClass origin, JavaClass target, List<String> violations) {
        String targetPackage = target.getPackageName();
        for (String forbidden : FORBIDDEN_LOGGING) {
            if (targetPackage.equals(forbidden) || targetPackage.startsWith(forbidden + ".")) {
                violations.add("[日志 API] " + origin.getName() + " → " + target.getName() + "：日志只用 slf4j");
            }
        }
    }
}
