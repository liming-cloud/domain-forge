package org.domainforge.cache.api;

import java.util.List;

/**
 * 执行已评审的 Lua 脚本；KEYS 必须在脚本声明的前缀内，ARGV 为字符串。
 */
public interface RedisScriptOperations {
    /**
     * 原子执行脚本；Redis 故障抛出 {@link CacheUnavailableException}，不自动重试。
     *
     * @param keys 1 至 16 个键
     * @param args 至多 32 个参数
     */
    <T> T execute(RedisScript<T> script, List<String> keys, List<String> args);
}
