package org.domainforge.buildrules;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.EvaluationResult;
import org.jmolecules.archunit.JMoleculesDddRules;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDD 规则：聚合引用与实体归属（jMolecules）、领域层不抛通用异常、应用层不翻译领域异常、仓储只针对聚合根、 一个用例方法只写一个聚合。
 *
 * <p>聚合根、实体、值对象由 jMolecules 注解或接口标识；仓储是 {@code domain.repository} 包中的类型。
 */
final class DddRules {
    private static final String AGGREGATE_ROOT = "org.jmolecules.ddd.annotation.AggregateRoot";
    private static final String ENTITY = "org.jmolecules.ddd.annotation.Entity";
    private static final String AGGREGATE_ROOT_TYPE = "org.jmolecules.ddd.types.AggregateRoot";
    private static final String ENTITY_TYPE = "org.jmolecules.ddd.types.Entity";

    private static final Set<String> GENERIC_EXCEPTIONS =
            Set.of(
                    "java.lang.IllegalArgumentException",
                    "java.lang.IllegalStateException",
                    "java.lang.UnsupportedOperationException",
                    "java.lang.RuntimeException",
                    "java.lang.Exception",
                    "java.lang.Throwable");

    private static final List<String> WRITE_PREFIXES =
            List.of("insert", "update", "delete", "replace", "invalidate", "save", "remove", "add");

    private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");

    private DddRules() {
    }

    static void check(JavaClasses classes, List<String> violations) {
        checkJMolecules(classes, violations);
        for (JavaClass type : classes) {
            String layer = layer(type);
            if ("domain".equals(layer)) {
                checkGenericExceptions(type, violations);
                checkRepository(type, violations);
            } else if ("application".equals(layer)) {
                checkCaughtDomainExceptions(type, violations);
                checkSingleAggregateWrites(type, violations);
            }
        }
    }

    private static String layer(JavaClass type) {
        ArchitectureRules.Location location = ArchitectureRules.locate(type.getPackageName());
        return location == null ? "" : location.layer();
    }

    private static void checkJMolecules(JavaClasses classes, List<String> violations) {
        EvaluationResult result = JMoleculesDddRules.all().evaluate(classes);
        if (result.hasViolation()) {
            result.getFailureReport().getDetails().forEach(detail -> violations.add("[DDD 模型] " + detail));
        }
    }

    private static void checkGenericExceptions(JavaClass type, List<String> violations) {
        type.getConstructorCallsFromSelf().stream()
                .filter(call -> GENERIC_EXCEPTIONS.contains(call.getTargetOwner().getName()))
                .forEach(call -> violations.add(
                        "[领域异常] "
                                + type.getName()
                                + "." + call.getOrigin().getName()
                                + "：领域层不得抛出 "
                                + call.getTargetOwner().getSimpleName()
                                + "，应使用 BusinessRuleViolation 子类"));
    }

    private static void checkCaughtDomainExceptions(JavaClass type, List<String> violations) {
        for (JavaCodeUnit unit : type.getCodeUnits()) {
            unit.getTryCatchBlocks().stream()
                    .flatMap(block -> block.getCaughtThrowables().stream())
                    .filter(caught -> "domain".equals(layer(caught)))
                    .forEach(caught -> violations.add(
                            "[领域异常] "
                                    + type.getName()
                                    + "." + unit.getName()
                                    + "：应用层不得捕获并翻译领域异常 "
                                    + caught.getName()));
        }
    }

    private static void checkRepository(JavaClass type, List<String> violations) {
        if (!isRepository(type)) {
            return;
        }
        boolean touchesRoot = false;
        for (JavaMethod method : type.getMethods()) {
            Set<JavaClass> involved = new HashSet<>(method.getReturnType().getAllInvolvedRawTypes());
            method.getParameterTypes().forEach(parameter -> involved.addAll(parameter.getAllInvolvedRawTypes()));
            for (JavaClass candidate : involved) {
                touchesRoot |= isAggregateRoot(candidate);
                if (isEntity(candidate) && !isAggregateRoot(candidate)) {
                    violations.add(
                            "[仓储] "
                                    + type.getName() + "." + method.getName()
                                    + " 使用了非聚合根实体 "
                                    + candidate.getName()
                                    + "：仓储只针对聚合根");
                }
            }
        }
        if (!touchesRoot) {
            violations.add("[仓储] " + type.getName() + " 的方法没有涉及任何聚合根：仓储只针对聚合根");
        }
    }

    private static boolean isRepository(JavaClass type) {
        return type.isInterface() && type.getPackageName().contains(".domain.repository");
    }

    private static boolean isAggregateRoot(JavaClass type) {
        return type.isAnnotatedWith(AGGREGATE_ROOT) || type.isAssignableTo(AGGREGATE_ROOT_TYPE);
    }

    private static boolean isEntity(JavaClass type) {
        return type.isAnnotatedWith(ENTITY) || type.isAssignableTo(ENTITY_TYPE);
    }

    /**
     * 同一个用例方法（含它的 lambda 与同类私有方法）调用了两个以上仓储的写方法，即在一个事务中修改多个聚合。
     */
    private static void checkSingleAggregateWrites(JavaClass type, List<String> violations) {
        Map<String, Set<String>> writes = new TreeMap<>();
        Map<String, Set<String>> calls = new TreeMap<>();
        for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
            String origin = entryName(call.getOrigin().getName());
            JavaClass owner = call.getTargetOwner();
            if (isRepository(owner) && isWrite(call.getName())) {
                writes.computeIfAbsent(origin, key -> new TreeSet<>()).add(owner.getSimpleName());
            } else if (owner.getName().equals(type.getName())) {
                calls.computeIfAbsent(origin, key -> new TreeSet<>()).add(entryName(call.getName()));
            }
        }
        Set<String> methods = new TreeSet<>(writes.keySet());
        methods.addAll(calls.keySet());
        for (String method : methods) {
            Set<String> repositories = reachableWrites(method, writes, calls);
            if (repositories.size() > 1) {
                violations.add(
                        "[单聚合事务] "
                                + type.getName() + "." + method
                                + " 写了多个聚合的仓储 "
                                + repositories
                                + "：一个事务只修改一个聚合，其余用集成事件最终一致");
            }
        }
    }

    private static Set<String> reachableWrites(
            String start, Map<String, Set<String>> writes, Map<String, Set<String>> calls) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(start));
        Set<String> repositories = new TreeSet<>();
        while (!pending.isEmpty()) {
            String method = pending.pop();
            if (seen.add(method)) {
                repositories.addAll(writes.getOrDefault(method, Set.of()));
                pending.addAll(calls.getOrDefault(method, Set.of()));
            }
        }
        return repositories;
    }

    private static boolean isWrite(String methodName) {
        return WRITE_PREFIXES.stream().anyMatch(methodName::startsWith);
    }

    /**
     * lambda 的合成方法归入定义它的方法。
     */
    private static String entryName(String name) {
        String current = name;
        Matcher matcher = LAMBDA.matcher(current);
        while (matcher.matches()) {
            current = matcher.group(1);
            matcher = LAMBDA.matcher(current);
        }
        return current;
    }
}
