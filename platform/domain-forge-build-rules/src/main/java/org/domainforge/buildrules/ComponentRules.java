package org.domainforge.buildrules;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.library.metrics.ArchitectureMetrics;
import com.tngtech.archunit.library.metrics.ComponentDependencyMetrics;
import com.tngtech.archunit.library.metrics.MetricsComponents;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 组件结构规则：模块内组件（框架组件的 api／internal／autoconfigure 子包，服务的职责包）之间无环、依赖指向更稳定的一方，
 * 组件 api 包保持纯净，查询实现不依赖领域仓储；并输出耦合度量报表（只报告，不拦截）。
 *
 * <p>不同模块之间的环由 Maven 依赖图禁止，这里只检查同一模块内的组件。
 */
final class ComponentRules {
    /** 被依赖方的不稳定度 I 比依赖方大出这个值即违规。 */
    private static final double STABILITY_TOLERANCE = 0.2;

    private static final Set<String> FRAMEWORK_SLICES = Set.of("api", "internal", "autoconfigure");

    private ComponentRules() {
    }

    /**
     * 组件标识：服务为“上下文根:职责段”，框架组件为“组件根:子包段”；不属于任何组件的类返回 null。
     */
    static String component(JavaClass type) {
        String packageName = type.getPackageName();
        ArchitectureRules.Location location = ArchitectureRules.locate(packageName);
        if (location != null) {
            return location.root() + ":" + location.layer();
        }
        if (!packageName.startsWith("org.domainforge.")) {
            return null;
        }
        List<String> segments = Arrays.asList(packageName.split("\\."));
        for (int index = 2; index < segments.size(); index++) {
            if (FRAMEWORK_SLICES.contains(segments.get(index))) {
                return String.join(".", segments.subList(0, index)) + ":" + segments.get(index);
            }
        }
        return null;
    }

    static void check(JavaClasses classes, List<String> violations) {
        Map<String, Set<String>> graph = componentGraph(classes);
        checkCycles(graph, violations);
        checkStability(classes, graph, violations);
        for (JavaClass type : classes) {
            checkApiPurity(type, violations);
            checkQueryShortcut(type, violations);
        }
    }

    /**
     * 各组件的 Ce、Ca、I、A、D 与整体 Lakos NCCD；用于报表。
     */
    static String report(JavaClasses classes) {
        MetricsComponents<JavaClass> components = components(classes);
        if (components.isEmpty()) {
            return "";
        }
        ComponentDependencyMetrics metrics = ArchitectureMetrics.componentDependencyMetrics(components);
        StringBuilder text = new StringBuilder("组件\tCe\tCa\tI\tA\tD\n");
        components.stream()
                .map(component -> component.getIdentifier())
                .sorted()
                .forEach(
                        id -> text.append(
                                String.format(
                                        "%s\t%d\t%d\t%.2f\t%.2f\t%.2f%n",
                                        id,
                                        metrics.getEfferentCoupling(id),
                                        metrics.getAfferentCoupling(id),
                                        metrics.getInstability(id),
                                        metrics.getAbstractness(id),
                                        metrics.getNormalizedDistanceFromMainSequence(id))));
        text.append(
                String.format(
                        "NCCD\t%.2f%n",
                        ArchitectureMetrics.lakosMetrics(components).getNormalizedCumulativeComponentDependency()));
        return text.toString();
    }

    private static MetricsComponents<JavaClass> components(JavaClasses classes) {
        List<JavaClass> members = classes.stream().filter(type -> component(type) != null).toList();
        return MetricsComponents.from(members, ComponentRules::component);
    }

    private static Map<String, Set<String>> componentGraph(JavaClasses classes) {
        Map<String, Set<String>> graph = new TreeMap<>();
        for (JavaClass type : classes) {
            String from = component(type);
            if (from == null) {
                continue;
            }
            graph.computeIfAbsent(from, key -> new TreeSet<>());
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String to = component(dependency.getTargetClass());
                if (to != null && !to.equals(from) && classes.contain(dependency.getTargetClass().getName())) {
                    graph.get(from).add(to);
                }
            }
        }
        return graph;
    }

    private static void checkCycles(Map<String, Set<String>> graph, List<String> violations) {
        Map<String, Set<String>> reachable = new HashMap<>();
        graph.keySet().forEach(node -> reachable.put(node, reach(graph, node)));
        for (String node : graph.keySet()) {
            Set<String> cycle =
                    reachable.get(node).stream()
                            .filter(other -> reachable.getOrDefault(other, Set.of()).contains(node))
                            .collect(Collectors.toCollection(TreeSet::new));
            if (!cycle.isEmpty()) {
                cycle.add(node);
                violations.add("[组件环] 组件之间存在循环依赖：" + String.join("、", cycle));
            }
        }
    }

    private static Set<String> reach(Map<String, Set<String>> graph, String start) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(graph.getOrDefault(start, Set.of()));
        while (!pending.isEmpty()) {
            String next = pending.pop();
            if (seen.add(next)) {
                pending.addAll(graph.getOrDefault(next, Set.of()));
            }
        }
        return seen;
    }

    private static void checkStability(
            JavaClasses classes, Map<String, Set<String>> graph, List<String> violations) {
        if (graph.isEmpty()) {
            return;
        }
        ComponentDependencyMetrics metrics =
                ArchitectureMetrics.componentDependencyMetrics(components(classes));
        graph.forEach(
                (from, targets) -> {
                    for (String to : targets) {
                        double gap = metrics.getInstability(to) - metrics.getInstability(from);
                        if (gap > STABILITY_TOLERANCE + 1e-9) {
                            violations.add(
                                    String.format(
                                            "[稳定依赖] %s（I=%.2f）依赖更不稳定的 %s（I=%.2f）",
                                            from,
                                            metrics.getInstability(from),
                                            to,
                                            metrics.getInstability(to)));
                        }
                    }
                });
    }

    /**
     * api 包只放接口（含注解）、record、enum、异常，以及没有状态的静态入口类。
     */
    private static void checkApiPurity(JavaClass type, List<String> violations) {
        if (!"api".equals(lastSegment(type.getPackageName())) || type.getEnclosingClass().isPresent()) {
            return;
        }
        boolean contract =
                type.isInterface() || type.isEnum() || type.isRecord() || type.isAssignableTo(Throwable.class);
        if (!contract && !statelessStaticEntry(type)) {
            violations.add(
                    "[api 纯净] "
                            + type.getName()
                            + "：api 包只放接口、record、enum、注解、异常和无状态的静态入口类，实现移入 internal");
        }
    }

    private static boolean statelessStaticEntry(JavaClass type) {
        boolean noInstanceState =
                type.getFields().stream().allMatch(field -> field.getModifiers().contains(JavaModifier.STATIC));
        boolean onlyStaticMethods =
                type.getMethods().stream()
                        .allMatch(method -> method.getModifiers().contains(JavaModifier.STATIC));
        return type.getModifiers().contains(JavaModifier.FINAL) && noInstanceState && onlyStaticMethods;
    }

    private static void checkQueryShortcut(JavaClass type, List<String> violations) {
        String packageName = "." + type.getPackageName() + ".";
        if (!packageName.contains(".application.query.") && !packageName.contains(".infrastructure.query.")) {
            return;
        }
        for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
            if (("." + dependency.getTargetClass().getPackageName() + ".").contains(".domain.repository.")) {
                violations.add(
                        "[查询短路径] "
                                + type.getName()
                                + " → "
                                + dependency.getTargetClass().getName()
                                + "：查询实现不得依赖领域仓储");
            }
        }
    }

    private static String lastSegment(String packageName) {
        return packageName.substring(packageName.lastIndexOf('.') + 1);
    }
}
