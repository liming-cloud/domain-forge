package org.domainforge.iam.interfaces.http.protocol;

import java.util.Collection;
import java.util.List;

/**
 * 雪花 ID 集合在 HTTP 中以十进制字符串传输，避免 JavaScript 精度丢失。
 */
public final class Ids {
    private Ids() {
    }

    /**
     * 升序的十进制字符串列表。
     */
    public static List<String> strings(Collection<Long> ids) {
        return ids.stream().sorted().map(String::valueOf).toList();
    }

    /**
     * 可空 ID 的十进制字符串。
     */
    public static String string(Long id) {
        return id == null ? null : id.toString();
    }
}
