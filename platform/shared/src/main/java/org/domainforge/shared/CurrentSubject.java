package org.domainforge.shared;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Set;

/**
 * 本次请求经过校验的当前主体：账号、角色与精确权限码；不构成对调用方任意业务资源的授权。
 *
 * <p>账号 ID 为雪花 long，JSON 中以十进制字符串传输，避免 JavaScript 精度丢失。
 */
public record CurrentSubject(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long accountId,
        Set<String> permissions,
        Set<String> roles) {
    /**
     * 没有角色授予的主体，角色为空集合而不是推定角色。
     */
    public CurrentSubject(long accountId, Set<String> permissions) {
        this(accountId, permissions, Set.of());
    }

    /**
     * 单次合同最多携带的角色数。
     */
    public static final int MAX_ROLES = 20;
    /**
     * 单次合同最多携带的精确权限数。
     */
    public static final int MAX_PERMISSIONS = 1000;

    /**
     * 只接受正数账号 ID 与有界的权限、角色集合，并复制集合；不携带令牌、HTTP 或持久化类型。超限明确失败，不截断。
     */
    public CurrentSubject {
        if (accountId <= 0
                || !bounded(roles, MAX_ROLES, "[A-Za-z][A-Za-z0-9:_-]{0,127}")
                || !bounded(permissions, MAX_PERMISSIONS, "[a-z][a-z0-9:-]{1,127}")) {
            throw new IllegalArgumentException("当前主体合同无效");
        }
        permissions = Set.copyOf(permissions);
        roles = Set.copyOf(roles);
    }

    /**
     * 集合非空引用、不超过上限且每项匹配给定格式。
     */
    private static boolean bounded(Set<String> values, int limit, String format) {
        return values != null
                && values.size() <= limit
                && values.stream().allMatch(value -> value != null && value.matches(format));
    }
}
