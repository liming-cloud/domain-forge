package org.domainforge.cache.internal;

import org.domainforge.cache.api.CacheUnavailableException;
import org.domainforge.cache.api.RedisScript;
import org.domainforge.cache.api.RedisScriptOperations;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只执行 classpath 中的脚本；首次使用时读取并缓存脚本文本，键须在脚本声明的前缀内。
 */
public final class RedissonScripts implements RedisScriptOperations {
    private final RedissonClient client;
    private final Map<String, String> sources = new ConcurrentHashMap<>();

    /**
     * 复用共享客户端。
     */
    public RedissonScripts(RedissonClient client) {
        this.client = client;
    }

    @Override
    public <T> T execute(RedisScript<T> script, List<String> keys, List<String> args) {
        requireInput(script, keys, args);
        String source = sources.computeIfAbsent(script.location(), RedissonScripts::load);
        Object result;
        try {
            result = client.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, source,
                    returnType(script.resultType()), List.copyOf(keys), args.toArray());
        } catch (RuntimeException failure) {
            throw new CacheUnavailableException("脚本执行失败或结果未知", failure);
        }
        return script.resultType().cast(result);
    }

    private static void requireInput(RedisScript<?> script, List<String> keys, List<String> args) {
        if (keys == null || keys.isEmpty() || keys.size() > 16 || args == null || args.size() > 32)
            throw new IllegalArgumentException("脚本须有1至16个键、至多32个参数");
        if (!keys.stream().allMatch(key -> key != null && key.startsWith(script.keyPrefix()) && key.length() <= 512))
            throw new IllegalArgumentException("脚本键须在声明的前缀内");
        if (args.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("脚本参数不能为空");
    }

    private static RScript.ReturnType returnType(Class<?> type) {
        if (type == Long.class) return RScript.ReturnType.LONG;
        if (type == Boolean.class) return RScript.ReturnType.BOOLEAN;
        return RScript.ReturnType.STRING;
    }

    private static String load(String location) {
        try {
            return new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new IllegalArgumentException("脚本资源不存在：" + location, missing);
        }
    }
}
