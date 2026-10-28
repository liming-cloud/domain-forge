package org.domainforge.messaging.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.domainforge.messaging.api.EventHandler;
import org.domainforge.messaging.api.ReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 为每个 {@link EventHandler} 启动一个推送消费者。每条消息在一个本地事务内先登记去重记录、再调用处理者：
 * 重复投递只确认不重复生效（计数 {@code domainforge.messaging.duplicate}）；处理失败回滚并交给服务端重投；
 * 最后一次投递仍失败时计数 {@code domainforge.messaging.dead-letter}
 * 并记录错误日志，服务端随后把消息转入死信队列。
 */
public final class EventConsumers implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(EventConsumers.class);

    private final List<EventHandler> handlers;
    private final ClientConfiguration client;
    private final InboxMapper inbox;
    private final TransactionOperations transactions;
    private final int maxAttempts;
    private final MeterRegistry metrics;
    private final List<PushConsumer> consumers = new ArrayList<>();

    /**
     * 处理者与连接配置由组件装配。
     *
     * @param maxAttempts 服务端消费组的最大投递次数（重试次数加 1），用于识别最后一次投递
     */
    public EventConsumers(List<EventHandler> handlers, ClientConfiguration client, InboxMapper inbox,
                          TransactionOperations transactions, int maxAttempts, MeterRegistry metrics) {
        this.handlers = List.copyOf(handlers);
        this.client = client;
        this.inbox = inbox;
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
        this.metrics = metrics;
    }

    /**
     * 消息信封转为事件；缺少事件 ID 的消息不是本平台发布的，按事件 ID 为消息 ID 处理以保证仍可去重。
     */
    static ReceivedEvent event(MessageView message) {
        Map<String, String> properties = message.getProperties();
        String id = properties.getOrDefault(Headers.EVENT_ID, message.getMessageId().toString());
        String version = properties.getOrDefault(Headers.EVENT_VERSION, "1");
        return new ReceivedEvent(id, properties.getOrDefault(Headers.EVENT_TYPE, message.getTag().orElse("")),
                Integer.parseInt(version), properties.getOrDefault(Headers.EVENT_KEY, ""),
                StandardCharsets.UTF_8.decode(message.getBody()).toString(), message.getDeliveryAttempt());
    }

    ConsumeResult consume(EventHandler handler, MessageView message) {
        ReceivedEvent event = event(message);
        try {
            Boolean fresh = transactions.execute(status -> {
                if (inbox.record(handler.group(), event.id()) == 0) return false;
                handler.handle(event);
                return true;
            });
            if (!Boolean.TRUE.equals(fresh)) {
                Counter.builder("domainforge.messaging.duplicate").tag("group", handler.group())
                        .register(metrics).increment();
                LOG.info("messaging_duplicate group={} event_id={}", handler.group(), event.id());
            }
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException failure) {
            if (event.attempt() >= maxAttempts) {
                Counter.builder("domainforge.messaging.dead-letter").tag("group", handler.group())
                        .register(metrics).increment();
                LOG.error("messaging_dead_letter group={} topic={} event_id={} type={} attempts={} reason={}",
                        handler.group(), handler.topic(), event.id(), event.type(), event.attempt(),
                        failure.getClass().getName());
            } else {
                LOG.warn("messaging_consume_failed group={} event_id={} attempt={} reason={}",
                        handler.group(), event.id(), event.attempt(), failure.getClass().getName());
            }
            return ConsumeResult.FAILURE;
        }
    }

    @Override
    public void start() {
        var provider = ClientServiceProvider.loadService();
        for (EventHandler handler : handlers) {
            try {
                consumers.add(provider.newPushConsumerBuilder()
                        .setClientConfiguration(client)
                        .setConsumerGroup(handler.group())
                        .setSubscriptionExpressions(Map.of(handler.topic(), FilterExpression.SUB_ALL))
                        .setMessageListener(message -> consume(handler, message))
                        .build());
            } catch (ClientException failure) {
                throw new IllegalStateException("无法启动消费组 " + handler.group(), failure);
            }
        }
    }

    @Override
    public void stop() {
        for (PushConsumer consumer : consumers) {
            try {
                consumer.close();
            } catch (java.io.IOException failure) {
                LOG.warn("messaging_consumer_close_failed group={}", consumer.getConsumerGroup());
            }
        }
        consumers.clear();
    }

    @Override
    public boolean isRunning() {
        return !consumers.isEmpty();
    }
}
