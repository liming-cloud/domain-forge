package @@PACKAGE@@.infrastructure.messaging;

import @@PACKAGE@@.application.contract.event.OrderSubmitted;
import @@PACKAGE@@.application.port.event.OrderEvents;
import org.domainforge.messaging.api.EventPublisher;
import org.domainforge.messaging.api.OutgoingEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 经消息组件的同事务 Outbox 发布；ID 以十进制字符串写入，与 HTTP 合同一致。
 */
@Component
public class OutboxOrderEvents implements OrderEvents {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EventPublisher publisher;

    /**
     * 只依赖消息组件的公开接口。
     */
    public OutboxOrderEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void submitted(OrderSubmitted event) {
        String payload = JSON.writeValueAsString(Map.of(
                "orderId", Long.toString(event.orderId()), "ownerId", Long.toString(event.ownerId()),
                "totalCents", Long.toString(event.totalCents())));
        publisher.publish(OutgoingEvent.unordered(
                OrderSubmitted.TOPIC, OrderSubmitted.TYPE, OrderSubmitted.VERSION, Long.toString(event.orderId()), payload));
    }
}
