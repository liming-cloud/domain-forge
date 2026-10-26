package org.domainforge.cache.internal;

import org.junit.jupiter.api.Test;
import org.redisson.api.RPatternTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisMaster;
import org.redisson.api.redisnode.RedisSingle;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 窄异常注入只证明资源清理控制流；实际通知与重连由 LocalCachingIT 验证。
 */
class CacheSubscriptionTest {
    private static RedisCacheRegions caches(RPatternTopic topic) {
        var config = new Config();
        config.useSingleServer();
        var node =
                proxy(
                        RedisMaster.class,
                        (object, method, arguments) ->
                                Map.of("notify-keyspace-events", "E$gx", "maxmemory-policy", "noeviction"));
        var single = proxy(RedisSingle.class, (object, method, arguments) -> node);
        var client =
                proxy(
                        RedissonClient.class,
                        (object, method, arguments) ->
                                switch (method.getName()) {
                                    case "getConfig" -> config;
                                    case "getRedisNodes" -> single;
                                    case "getPatternTopic" -> topic;
                                    default -> throw new UnsupportedOperationException(method.getName());
                                });
        return new RedisCacheRegions(
                client,
                List.of(
                        new RedisCacheRegion.Definition<>(
                                "test", String.class, StringCodec.INSTANCE, 8, Duration.ofSeconds(1))));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    @Test
    void cleanupAttemptsBothOwnedListenersWhenFirstRemovalFails() {
        var removed = new ArrayList<Integer>();
        var failed = new IllegalStateException("first removal");
        var topic =
                proxy(
                        RPatternTopic.class,
                        (object, method, arguments) -> {
                            if (method.getName().equals("addListener")) {
                                return arguments.length == 1 ? 1 : 2;
                            }
                            int id = ((Integer[]) arguments[0])[0];
                            removed.add(id);
                            if (id == 2) {
                                throw failed;
                            }
                            return null;
                        });
        var caches = caches(topic);
        assertSame(failed, assertThrows(IllegalStateException.class, caches::close));
        assertEquals(List.of(2, 1), removed, "清理第一个监听器失败不能漏掉第二个");
    }

    @Test
    void constructionFailureRetainsOriginalAndCleanupFailure() {
        var original = new IllegalStateException("subscribe");
        var cleanup = new IllegalStateException("cleanup");
        var topic =
                proxy(
                        RPatternTopic.class,
                        (object, method, arguments) -> {
                            if (method.getName().equals("addListener")) {
                                if (arguments.length == 1) {
                                    return 1;
                                }
                                throw original;
                            }
                            throw cleanup;
                        });
        var failure = assertThrows(IllegalStateException.class, () -> caches(topic));
        assertSame(original, failure);
        assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
    }
}
