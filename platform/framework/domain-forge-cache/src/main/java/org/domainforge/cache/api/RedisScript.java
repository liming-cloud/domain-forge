package org.domainforge.cache.api;

/**
 * 已评审的 classpath Lua 脚本：固定位置、返回类型与可操作的键前缀，不接受外部脚本文本。
 *
 * <p>脚本在 Redis 中原子执行，但执行中途出错不会回滚已完成的写入；超时不等于取消，调用方不应盲目重试。
 *
 * @param location   classpath 资源位置，例如 {@code scripts/stock-reserve.lua}
 * @param resultType 返回类型：Long、String 或 Boolean
 * @param keyPrefix  所有 KEYS 必须以此前缀开头，例如 {@code order:stock:}；不能使用平台保留前缀
 * @param <T>        返回类型
 */
public record RedisScript<T>(String location, Class<T> resultType, String keyPrefix) {
    /**
     * 校验位置、返回类型与键前缀。
     */
    public RedisScript {
        if (location == null || !location.matches("[a-zA-Z0-9_/.-]{1,200}\\.lua") || location.contains(".."))
            throw new IllegalArgumentException("脚本位置须为 classpath 中的 .lua 资源");
        if (resultType != Long.class && resultType != String.class && resultType != Boolean.class)
            throw new IllegalArgumentException("脚本返回类型只支持 Long、String、Boolean");
        requirePrefix(keyPrefix);
    }

    private static void requirePrefix(String keyPrefix) {
        if (keyPrefix == null || !keyPrefix.matches("[a-z][a-z0-9-]{1,31}:[a-z0-9:_-]{0,96}")
                || keyPrefix.startsWith("satoken:") || keyPrefix.startsWith("domainforge:"))
            throw new IllegalArgumentException("键前缀须为服务自己的命名空间，不能操作会话或平台保留键");
    }
}
