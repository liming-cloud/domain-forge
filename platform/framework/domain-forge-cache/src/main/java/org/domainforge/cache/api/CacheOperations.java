package org.domainforge.cache.api;

/**
 * 缓存入口：按名称取得在配置 {@code domainforge.cache.regions.<name>} 中声明的区域。
 */
public interface CacheOperations {
    /**
     * 取得已声明的区域；未声明或值类型与配置不符时抛出 IllegalArgumentException。
     */
    <T> CacheRegion<T> region(String name, Class<T> type);
}
