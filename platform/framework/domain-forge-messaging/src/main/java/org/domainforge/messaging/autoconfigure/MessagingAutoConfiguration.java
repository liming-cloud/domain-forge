package org.domainforge.messaging.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.domainforge.messaging.api.EventHandler;
import org.domainforge.messaging.api.EventPublisher;
import org.domainforge.messaging.internal.EventConsumers;
import org.domainforge.messaging.internal.InboxMapper;
import org.domainforge.messaging.internal.OutboxMapper;
import org.domainforge.messaging.internal.OutboxPublisher;
import org.domainforge.messaging.internal.OutboxRelay;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.List;

/**
 * 消息组件装配：发布（{@code domainforge.messaging.outbox.enabled}）与消费（存在 {@link EventHandler} Bean）各自独立启用。
 *
 * <p>需要连接 RocketMQ 时（启用中继或有处理者）必须配置 {@code domainforge.messaging.endpoints}，缺失即启动失败；
 * 只有测试可以用 {@code domainforge.messaging.relay.enabled=false} 只写 Outbox 不发送、
 * {@code domainforge.messaging.consumer.enabled=false} 只装配处理者不连接消费组。
 */
@AutoConfiguration
@MapperScan(basePackageClasses = OutboxMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class MessagingAutoConfiguration {
    private static boolean flag(Environment environment, String name, boolean fallback) {
        return environment.getProperty(name, Boolean.class, fallback);
    }

    private static Duration duration(Environment environment, String name, Duration fallback) {
        return environment.getProperty(name, Duration.class, fallback);
    }

    /**
     * 连接 RocketMQ Proxy 的客户端配置；未配置地址时为空。
     */
    @Bean
    MessagingClient messagingClient(Environment environment) {
        String endpoints = environment.getProperty("domainforge.messaging.endpoints", "");
        if (endpoints.isBlank()) return new MessagingClient(null);
        return new MessagingClient(ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(false)
                .setRequestTimeout(
                        duration(environment, "domainforge.messaging.request-timeout", Duration.ofSeconds(3)))
                .build());
    }

    /**
     * 发布接口：只在启用 Outbox 的服务中存在。
     */
    @Bean
    EventPublisher eventPublisher(Environment environment, OutboxMapper outbox, ObjectProvider<OutboxRelay> relay) {
        if (!flag(environment, "domainforge.messaging.outbox.enabled", false))
            return event -> {
                throw new IllegalStateException("未启用 Outbox（domainforge.messaging.outbox.enabled）");
            };
        return new OutboxPublisher(outbox, () -> relay.ifAvailable(OutboxRelay::wake));
    }

    /**
     * 中继与清理：发布方发送 Outbox、清理已发送记录；消费方清理去重记录。
     */
    @Bean
    OutboxRelay outboxRelay(Environment environment, MessagingClient client, OutboxMapper outbox, InboxMapper inbox,
                            TransactionOperations transactions, ObjectProvider<EventHandler> handlers,
                            ObjectProvider<MeterRegistry> metrics) throws ClientException {
        boolean publishing = flag(environment, "domainforge.messaging.outbox.enabled", false)
                && flag(environment, "domainforge.messaging.relay.enabled", true);
        boolean consuming = handlers.stream().findAny().isPresent();
        Producer producer = publishing
                ? ClientServiceProvider.loadService().newProducerBuilder()
                .setClientConfiguration(client.require()).build()
                : null;
        var settings = new OutboxRelay.Settings(
                duration(environment, "domainforge.messaging.relay.interval", Duration.ofSeconds(1)),
                environment.getProperty("domainforge.messaging.relay.batch-size", Integer.class, 50),
                environment.getProperty("domainforge.messaging.retention-hours", Long.class, 168L),
                publishing, consuming);
        return new OutboxRelay(outbox, inbox, transactions, producer, settings,
                metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 每个处理者一个消费组；没有处理者时不连接。
     */
    @Bean
    EventConsumers eventConsumers(Environment environment, MessagingClient client,
                                  ObjectProvider<EventHandler> handlers, InboxMapper inbox,
                                  TransactionOperations transactions, ObjectProvider<MeterRegistry> metrics) {
        List<EventHandler> all = flag(environment, "domainforge.messaging.consumer.enabled", true)
                ? handlers.orderedStream().toList() : List.of();
        return new EventConsumers(all, all.isEmpty() ? null : client.require(), inbox, transactions,
                environment.getProperty("domainforge.messaging.consumer.max-attempts", Integer.class, 5),
                metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * RocketMQ 客户端配置的持有者；需要连接却未配置地址时启动失败。
     *
     * @param configuration 客户端配置，未配置地址时为空
     */
    record MessagingClient(ClientConfiguration configuration) {
        ClientConfiguration require() {
            if (configuration == null)
                throw new IllegalStateException("需要连接 RocketMQ，但未配置 domainforge.messaging.endpoints");
            return configuration;
        }
    }
}
