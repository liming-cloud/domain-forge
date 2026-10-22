package org.domainforge.cache.autoconfigure;

import org.redisson.config.Protocol;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 复用 Redisson 原生资源装配，地址和凭据只来自 spring.data.redis。 */
@AutoConfiguration(beforeName = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
public class CacheConfiguration {
  /** 固定 RESP3 与有限资源；不自动重试结果未知的写入。 */
  @Bean
  RedissonAutoConfigurationCustomizer domainForgeRedisson(Environment environment) {
    return config -> {
      config.setProtocol(Protocol.RESP3);
      config.setThreads(number(environment, "threads", 1));
      config.setNettyThreads(number(environment, "netty-threads", 1));
      var server = config.useSingleServer();
      server.setConnectionPoolSize(number(environment, "connection-pool-size", 1));
      server.setConnectionMinimumIdleSize(number(environment, "connection-minimum-idle-size", 1));
      server.setSubscriptionConnectionPoolSize(number(environment, "subscription-pool-size", 1));
      server.setSubscriptionConnectionMinimumIdleSize(
          number(environment, "subscription-minimum-idle-size", 1));
      int attempts = number(environment, "retry-attempts", 0);
      if (attempts != 0) {
        throw new IllegalArgumentException("共享认证资源不允许自动重试未知结果的写入");
      }
      server.setRetryAttempts(attempts);
    };
  }

  /** 默认值只在 YAML，配置缺失或非法不静默回退。 */
  private static int number(Environment environment, String key, int minimum) {
    int value = environment.getRequiredProperty("domainforge.cache." + key, Integer.class);
    if (value < minimum || value > 256) {
      throw new IllegalArgumentException("缓存资源参数超出支持范围：" + key);
    }
    return value;
  }
}
