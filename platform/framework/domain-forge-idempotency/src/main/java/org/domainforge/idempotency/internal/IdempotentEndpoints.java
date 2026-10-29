package org.domainforge.idempotency.internal;

import org.domainforge.idempotency.api.Idempotent;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 标了 {@link Idempotent} 的入口：方法与路径模式，取自 MVC 的映射表（首次使用时读取一次）。
 */
class IdempotentEndpoints {
    private final List<RequestMappingHandlerMapping> mappings;
    private volatile List<Endpoint> endpoints;

    IdempotentEndpoints(List<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    /**
     * 请求是否命中幂等入口。
     */
    boolean matches(String method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return endpoints().stream().anyMatch(endpoint -> endpoint.matches(method, container));
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> current = endpoints;
        if (current != null) return current;
        List<Endpoint> found = new ArrayList<>();
        for (RequestMappingHandlerMapping mapping : mappings) {
            mapping.getHandlerMethods().forEach((info, handler) -> {
                if (handler.hasMethodAnnotation(Idempotent.class)) found.add(endpoint(info));
            });
        }
        endpoints = List.copyOf(found);
        return endpoints;
    }

    private static Endpoint endpoint(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() == null)
            throw new IllegalStateException("幂等入口须使用 PathPattern 路径匹配：" + info);
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        return new Endpoint(methods.stream().map(RequestMethod::name).toList(),
                List.copyOf(info.getPathPatternsCondition().getPatterns()));
    }

    /**
     * 一个入口。
     *
     * @param methods  HTTP 方法；为空表示任意方法
     * @param patterns 路径模式
     */
    record Endpoint(List<String> methods, List<PathPattern> patterns) {
        boolean matches(String method, PathContainer path) {
            return (methods.isEmpty() || methods.contains(method))
                    && patterns.stream().anyMatch(pattern -> pattern.matches(path));
        }
    }
}
