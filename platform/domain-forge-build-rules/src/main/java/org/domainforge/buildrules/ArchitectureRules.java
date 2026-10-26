package org.domainforge.buildrules;

import com.tngtech.archunit.core.domain.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 平台可判定的架构规则；只检查依赖方向与已知技术边界，不判断业务语义。
 *
 * <p>业务上下文按包名中的五个职责段识别：bootstrap、interfaces、application、domain、infrastructure。 职责段之前的包即上下文根。
 */
public final class ArchitectureRules {
    static final List<String> LAYERS =
            List.of("bootstrap", "interfaces", "application", "domain", "infrastructure");

    private static final Map<String, Set<String>> ALLOWED_LAYERS =
            Map.of(
                    "domain", Set.of("domain"),
                    "application", Set.of("application", "domain"),
                    "interfaces", Set.of("interfaces", "application", "domain"),
                    "infrastructure", Set.of("interfaces", "application", "domain", "infrastructure"),
                    "bootstrap", Set.copyOf(LAYERS));

    private static final Set<String> APPLICATION_SPRING_TYPES =
            Set.of(
                    "org.springframework.stereotype.Component",
                    "org.springframework.stereotype.Service",
                    "org.springframework.beans.factory.annotation.Autowired",
                    "org.springframework.beans.factory.annotation.Qualifier",
                    "org.springframework.transaction.annotation.Transactional",
                    "org.springframework.transaction.annotation.Propagation",
                    "org.springframework.transaction.annotation.Isolation",
                    // 编程式短事务（慢操作放在事务外时使用），见持久化规范 P-11
                    "org.springframework.transaction.support.TransactionOperations",
                    "org.springframework.transaction.support.TransactionCallback",
                    "org.springframework.transaction.TransactionStatus");

    private static final List<String> FORBIDDEN_JDK = List.of("java.sql", "java.net.http");

    private static final List<String> INTERFACES_FORBIDDEN =
            List.of(
                    "java.sql",
                    "javax.sql",
                    "org.springframework.jdbc",
                    "org.apache.ibatis",
                    "org.mybatis",
                    "com.baomidou",
                    "org.redisson",
                    "io.lettuce",
                    "org.springframework.data.redis");

    private static final Set<String> SQL_ANNOTATIONS =
            Set.of(
                    "org.apache.ibatis.annotations.Select",
                    "org.apache.ibatis.annotations.Insert",
                    "org.apache.ibatis.annotations.Update",
                    "org.apache.ibatis.annotations.Delete",
                    "org.apache.ibatis.annotations.SelectProvider",
                    "org.apache.ibatis.annotations.InsertProvider",
                    "org.apache.ibatis.annotations.UpdateProvider",
                    "org.apache.ibatis.annotations.DeleteProvider");

    private static final Set<String> RAW_WRAPPER_METHODS =
            Set.of(
                    "apply",
                    "last",
                    "setSql",
                    "inSql",
                    "notInSql",
                    "eqSql",
                    "gtSql",
                    "geSql",
                    "ltSql",
                    "leSql",
                    "exists",
                    "notExists",
                    "having");

    private ArchitectureRules() {
    }

    /**
     * 检查一组已导入的生产类，返回排序去重后的违规说明；空列表表示通过。
     *
     * @param classes 同一模块的生产类
     * @return 违规说明
     */
    public static List<String> check(JavaClasses classes) {
        Set<String> roots =
                classes.stream()
                        .map(type -> locate(type.getPackageName()))
                        .filter(Objects::nonNull)
                        .map(Location::root)
                        .collect(Collectors.toSet());
        List<String> violations = new ArrayList<>();
        for (JavaClass type : classes) {
            Location from = locate(type.getPackageName());
            checkOwnership(type, from, roots, violations);
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                checkDependency(type, from, dependency.getTargetClass(), violations);
            }
            checkSqlAnnotations(type, violations);
            checkSqlCalls(type, violations);
        }
        return violations.stream().distinct().sorted().toList();
    }

    /**
     * 找到第一个职责段，职责段之前为上下文根。
     */
    static Location locate(String packageName) {
        List<String> segments = Arrays.asList(packageName.split("\\."));
        for (int index = 1; index < segments.size(); index++) {
            if (LAYERS.contains(segments.get(index))) {
                return new Location(String.join(".", segments.subList(0, index)), segments.get(index));
            }
        }
        return null;
    }

    private static void checkOwnership(
            JavaClass type, Location from, Set<String> roots, List<String> violations) {
        if (from != null) {
            return;
        }
        String name = type.getPackageName();
        for (String root : roots) {
            if (inside(name, root)) {
                violations.add("[职责归属] " + type.getName() + " 位于上下文 " + root + " 内，但不在五个职责包之一");
            }
        }
    }

    private static void checkDependency(
            JavaClass origin, Location from, JavaClass rawTarget, List<String> violations) {
        JavaClass target = rawTarget.isArray() ? rawTarget.getBaseComponentType() : rawTarget;
        if (target.isPrimitive() || target.getName().equals(origin.getName())) {
            return;
        }
        String targetPackage = target.getPackageName();
        checkInternal(origin, target, violations);
        if (from == null) {
            return;
        }
        Location to = locate(targetPackage);
        if (to != null) {
            checkLayer(origin, from, target, to, violations);
            return;
        }
        String problem = technicalProblem(from.layer(), target);
        if (problem != null) {
            violations.add("[技术边界] " + origin.getName() + " → " + target.getName() + "：" + problem);
        }
    }

    private static void checkLayer(
            JavaClass origin, Location from, JavaClass target, Location to, List<String> violations) {
        if (!to.root().equals(from.root())) {
            if (!target.getPackageName().contains(".application.contract")) {
                violations.add(
                        "[跨上下文] "
                                + origin.getName()
                                + " → "
                                + target.getName()
                                + "：只能引用对方 application.contract 公开合同");
            }
            return;
        }
        if (!ALLOWED_LAYERS.get(from.layer()).contains(to.layer())) {
            violations.add(
                    "[依赖方向] "
                            + origin.getName()
                            + " → "
                            + target.getName()
                            + "："
                            + from.layer()
                            + " 不得依赖 "
                            + to.layer());
        }
    }

    private static String technicalProblem(String layer, JavaClass target) {
        String name = target.getName();
        String targetPackage = target.getPackageName();
        return switch (layer) {
            case "domain" -> domainAllowed(targetPackage) ? null : "领域只能依赖 JDK 与 org.domainforge.shared";
            case "application" -> domainAllowed(targetPackage)
                    || componentApi(targetPackage)
                    || APPLICATION_SPRING_TYPES.contains(name)
                    ? null
                    : "应用层只能依赖 JDK、shared、组件 api 包及允许的 Spring 装配／事务类型";
            case "interfaces" -> startsWithAny(targetPackage, INTERFACES_FORBIDDEN)
                    ? "入口层不得直接使用 SQL／Mapper／Redis 客户端"
                    : null;
            default -> null;
        };
    }

    private static boolean domainAllowed(String targetPackage) {
        boolean jdk = inside(targetPackage, "java") && !startsWithAny(targetPackage, FORBIDDEN_JDK);
        return jdk || inside(targetPackage, "org.domainforge.shared");
    }

    private static boolean componentApi(String targetPackage) {
        List<String> segments = Arrays.asList(targetPackage.split("\\."));
        return inside(targetPackage, "org.domainforge") && segments.contains("api");
    }

    private static void checkInternal(JavaClass origin, JavaClass target, List<String> violations) {
        String targetPackage = target.getPackageName();
        int index = (targetPackage + ".").indexOf(".internal.");
        if (!inside(targetPackage, "org.domainforge") || index < 0) {
            return;
        }
        String owner = targetPackage.substring(0, index);
        if (!inside(origin.getPackageName(), owner)) {
            violations.add(
                    "[内部实现] " + origin.getName() + " → " + target.getName() + "：不得访问其他组件的 internal 包");
        }
    }

    private static void checkSqlAnnotations(JavaClass type, List<String> violations) {
        for (JavaMethod method : type.getMethods()) {
            for (String annotation : SQL_ANNOTATIONS) {
                if (method.isAnnotatedWith(annotation)) {
                    violations.add(
                            "[SQL 位置] "
                                    + type.getName()
                                    + "."
                                    + method.getName()
                                    + "：SQL 必须写在 Mapper XML，禁止 @"
                                    + annotation.substring(annotation.lastIndexOf('.') + 1));
                }
            }
        }
    }

    private static void checkSqlCalls(JavaClass type, List<String> violations) {
        for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
            if (rawSqlCall(call)) {
                violations.add(
                        "[SQL 位置] "
                                + type.getName()
                                + " 调用 "
                                + call.getTargetOwner().getName()
                                + "."
                                + call.getName()
                                + "：禁止拼接原始 SQL");
            }
        }
    }

    private static boolean rawSqlCall(JavaMethodCall call) {
        String owner = call.getTargetOwner().getName();
        String name = call.getName();
        boolean stringArgument =
                call.getTarget().getRawParameterTypes().stream()
                        .anyMatch(parameter -> parameter.getName().equals("java.lang.String"));
        if (owner.startsWith("com.baomidou.mybatisplus.core.conditions.")) {
            return RAW_WRAPPER_METHODS.contains(name);
        }
        if (owner.startsWith("com.baomidou.mybatisplus.extension.conditions.")) {
            return RAW_WRAPPER_METHODS.contains(name) && stringArgument;
        }
        if (owner.equals("java.sql.Connection")) {
            return Set.of("prepareStatement", "prepareCall", "nativeSQL").contains(name);
        }
        return owner.equals("java.sql.Statement") && stringArgument;
    }

    private static boolean startsWithAny(String targetPackage, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> inside(targetPackage, prefix));
    }

    private static boolean inside(String targetPackage, String prefix) {
        return targetPackage.equals(prefix) || targetPackage.startsWith(prefix + ".");
    }

    /**
     * 上下文根及职责段；不在上下文中的类返回 null。
     */
    record Location(String root, String layer) {
    }
}
