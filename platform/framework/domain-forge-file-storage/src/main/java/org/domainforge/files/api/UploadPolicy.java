package org.domainforge.files.api;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务用例允许的上传：大小上限与类型白名单。服务的全局上限 {@code domainforge.files.max-size} 同时生效，取较小者。
 *
 * @param maxBytes     最大字节数，大于 0
 * @param contentTypes 允许的类型（不含参数，如 {@code image/png}），不能为空
 */
public record UploadPolicy(long maxBytes, Set<String> contentTypes) {
    /**
     * 类型统一为小写。
     */
    public UploadPolicy {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes 必须大于 0");
        if (contentTypes == null || contentTypes.isEmpty())
            throw new IllegalArgumentException("contentTypes 不能为空");
        contentTypes = contentTypes.stream().map(type -> type.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 便捷构造。
     */
    public static UploadPolicy of(long maxBytes, String... contentTypes) {
        return new UploadPolicy(maxBytes, Set.of(contentTypes));
    }
}
