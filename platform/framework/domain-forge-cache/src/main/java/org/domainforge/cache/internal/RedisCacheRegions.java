package org.domainforge.cache.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.redisson.api.RPatternTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.PatternStatusListener;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.client.codec.StringCodec;

/** 固定 region 集合共享一条原生订阅；生命周期不拥有或关闭共享客户端。 */
final class RedisCacheRegions implements AutoCloseable {
  private final Map<String, RedisCacheRegion<?>> regions = new LinkedHashMap<>();
  private RPatternTopic notifications;
  private final List<Integer> listeners = new ArrayList<>(2);

  RedisCacheRegions(RedissonClient client, List<RedisCacheRegion.Definition<?>> definitions) {
    if (definitions.size() > 64) {
      throw new IllegalArgumentException("缓存 region 最多 64 个");
    }
    for (var definition : definitions) {
      if (regions.putIfAbsent(definition.name(), new RedisCacheRegion<>(client, definition))
          != null) {
        throw new IllegalArgumentException("同一 region 只能声明一种值类型与 codec");
      }
    }
    if (regions.values().stream().noneMatch(RedisCacheRegion::localEnabled)) {
      return;
    }
    // 本轮仅验证单 Redis；不得猜测集群数据库或遗漏节点通知。
    if (!client.getConfig().isSingleConfig()) {
      throw new IllegalArgumentException("当前 L1 仅支持已验证的单 Redis 配置");
    }
    var single = client.getConfig().useSingleServer();
    var node = client.getRedisNodes(RedisNodes.SINGLE).getInstance();
    String flags = node.getConfig("notify-keyspace-events").get("notify-keyspace-events");
    String eviction = node.getConfig("maxmemory-policy").get("maxmemory-policy");
    verifyNotifications(flags, eviction);
    notifications =
        client.getPatternTopic("__keyevent@" + single.getDatabase() + "__:*", StringCodec.INSTANCE);
    try {
      listeners.add(
          notifications.addListener(
              new PatternStatusListener() {
                @Override
                public void onPSubscribe(String pattern) {
                  clear();
                }

                @Override
                public void onPUnsubscribe(String pattern) {
                  clear();
                }
              }));
      listeners.add(
          0,
          notifications.addListener(
              String.class,
              (pattern, channel, key) ->
                  regions.values().forEach(region -> region.invalidate(key))));
    } catch (RuntimeException failure) {
      try {
        close();
      } catch (RuntimeException cleanup) {
        if (cleanup != failure) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
  }

  private static void verifyNotifications(String flags, String eviction) {
    String enabled = flags == null ? "" : flags;
    if (enabled.contains("A")) {
      enabled += "g$xe";
    }
    boolean events = List.of("E", "$", "g", "x").stream().allMatch(enabled::contains);
    boolean evictions = "noeviction".equals(eviction) || enabled.contains("e");
    if (!events || !evictions) {
      throw new IllegalStateException("启用 L1 需要 Redis 原生 E$gx 通知；淘汰模式还需要 e 事件");
    }
  }

  /** 只能取得预先声明的 region，不按调用动态扩容或改变值类型。 */
  @SuppressWarnings("unchecked")
  <T> RedisCacheRegion<T> region(String name, Class<T> type) {
    var region = regions.get(name);
    if (region == null || region.type() != type) {
      throw new IllegalArgumentException("缓存 region 未声明或值类型不匹配");
    }
    return (RedisCacheRegion<T>) region;
  }

  private void clear() {
    regions.values().forEach(RedisCacheRegion::clear);
  }

  /** 只移除自身两个监听器，不能清除 Sa-Token 或其他组件的订阅。 */
  @Override
  public void close() {
    regions.values().forEach(RedisCacheRegion::close);
    RuntimeException failure = null;
    for (Integer id : List.copyOf(listeners)) {
      try {
        notifications.removeListener(id);
        listeners.remove(id);
      } catch (RuntimeException cleanup) {
        if (failure == null) {
          failure = cleanup;
        } else if (failure != cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
