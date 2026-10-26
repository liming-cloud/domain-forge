package org.domainforge.cache.api;

/**
 * 缓存不可用：Redis 故障、回源并发已满或等待超时。调用方按依赖不可用处理，不能当作未命中或成功。
 */
public final class CacheUnavailableException extends RuntimeException {
    /**
     * 消息为固定说明，不含键或值。
     */
    public CacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
