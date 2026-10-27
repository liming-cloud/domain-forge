package org.domainforge.gateway.infrastructure.routing;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一条网关路由：转发到哪个服务、哪些公开路径、其中哪些无需登录。只开放这三项，不开放任意谓词或过滤器。
 *
 * @param id        路由标识，配置内唯一
 * @param service   Nacos 中的服务名，转发到 {@code lb://<service>}
 * @param paths     公开路径模式，以 /api/ 开头，不含 internal 段或 ..
 * @param anonymous 无需登录的接口，形如 {@code POST /api/auth/login}，必须落在本路由的路径内
 */
public record RouteSettings(String id, String service, List<String> paths, List<String> anonymous) {
    private static final String NAME = "[a-z][a-z0-9-]{0,62}";
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /**
     * 校验全部字段；任何一项非法都使整组配置不可应用。
     */
    public RouteSettings {
        if (id == null || !id.matches(NAME)) throw invalid("路由标识不合法: " + id);
        if (service == null || !service.matches(NAME)) throw invalid("服务名不合法: " + service);
        if (paths == null || paths.isEmpty()) throw invalid("路由 " + id + " 缺少路径");
        paths = List.copyOf(paths);
        anonymous = List.copyOf(Objects.requireNonNullElse(anonymous, List.of()));
        List<PathPattern> patterns = paths.stream().map(RouteSettings::pattern).toList();
        for (String entry : anonymous) anonymousEndpoint(id, entry, patterns);
    }

    /**
     * 公开路径的模式；越过 /api/ 前缀、指向 internal 段或含 .. 的路径一律拒绝。
     */
    static PathPattern pattern(String path) {
        if (path == null || !path.startsWith("/api/") || path.contains("..") || path.contains("//")
                || (path + "/").contains("/internal/")) throw invalid("路径不合法: " + path);
        try {
            return PathPatternParser.defaultInstance.parse(path);
        } catch (RuntimeException syntax) {
            throw invalid("路径模式无法解析: " + path);
        }
    }

    /**
     * 判断请求是否命中本路由的匿名清单。
     */
    public boolean allowsAnonymous(String method, PathContainer path) {
        for (String entry : anonymous) {
            int space = entry.indexOf(' ');
            if (entry.substring(0, space).equals(method) && entry.substring(space + 1).equals(path.value()))
                return true;
        }
        return false;
    }

    private static void anonymousEndpoint(String id, String entry, List<PathPattern> patterns) {
        int space = entry == null ? -1 : entry.indexOf(' ');
        if (space < 0 || !METHODS.contains(entry.substring(0, space))) throw invalid("匿名接口格式不合法: " + entry);
        String path = entry.substring(space + 1);
        if (path.contains("*") || path.contains("{")) throw invalid("匿名接口必须是确切路径: " + entry);
        pattern(path);
        PathContainer container = PathContainer.parsePath(path);
        if (patterns.stream().noneMatch(pattern -> pattern.matches(container)))
            throw invalid("匿名接口不在路由 " + id + " 的路径内: " + entry);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
