package org.domainforge.iam.interfaces.http.protocol;

import org.domainforge.iam.application.port.IamException;
import org.jspecify.annotations.Nullable;

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
    public static @Nullable String string(@Nullable Long id) {
        return id == null ? null : id.toString();
    }

    /**
     * 解析请求中的十进制 ID；缺失或格式无效按输入无效拒绝（400），不落成 500。
     */
    public static long parse(@Nullable String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw IamException.invalid("ID 格式无效");
        }
    }
}
