package @@PACKAGE@@.infrastructure.acl.iam;

import cn.dev33.satoken.context.SaHolder;
import org.domainforge.shared.CurrentScope;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 读当前主体时一并向 IAM 取回的数据范围：权限取自本服务权限目录（{@code iam/permissions.json}）中需要数据范围的项。
 *
 * <p>取回的范围只放在本请求的 Sa-Token 存储中并绑定令牌，请求结束即丢弃，不跨请求缓存。没有目录、目录中没有需要范围的
 * 权限或超过 IAM 单次上限（32 个）时不预取，数据范围按需单独读取。
 */
@Component
public class ScopePrefetch {
    private static final String CATALOG = "iam/permissions.json";
    private static final int MAX_BATCH = 32;
    private static final String KEY = ScopePrefetch.class.getName();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final List<String> permissions = load();

    /**
     * 要预取范围的权限码；为空时不预取。
     */
    List<String> permissions() {
        return permissions;
    }

    /**
     * 记下本请求为该令牌取回的范围。
     */
    void remember(String token, Map<String, CurrentScope> scopes) {
        SaHolder.getStorage().set(KEY, new Fetched(token, Map.copyOf(scopes)));
    }

    /**
     * 本请求已为同一令牌取回的范围；没有时返回 null，由调用方单独读取。
     */
    @Nullable CurrentScope recall(String token, String permission) {
        Object value = SaHolder.getStorage().get(KEY);
        return value instanceof Fetched fetched && fetched.token().equals(token) ? fetched.scopes().get(permission) : null;
    }

    private static List<String> load() {
        try (InputStream in = ScopePrefetch.class.getClassLoader().getResourceAsStream(CATALOG)) {
            if (in == null) return List.of();
            List<String> codes = new ArrayList<>();
            for (JsonNode permission : JSON.readTree(in).path("permissions"))
                if (permission.path("scoped").asBoolean()) codes.add(permission.path("code").asString());
            return codes.size() <= MAX_BATCH ? List.copyOf(codes) : List.of();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * 令牌与它的范围；默认字符串不暴露令牌。
     */
    private record Fetched(String token, Map<String, CurrentScope> scopes) {
        @Override
        public String toString() {
            return "Fetched" + scopes.keySet();
        }
    }
}
