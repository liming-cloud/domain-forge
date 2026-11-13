package @@PACKAGE@@.domain.event;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * 领域事件：订单已提交（本上下文内的业务事实）。由聚合在提交时产生，应用层据此映射对外的集成事件。
 *
 * @param orderId    订单 ID
 * @param ownerId    下单人账号 ID
 * @param totalCents 订单总额（分）
 */
@DomainEvent
public record OrderSubmitted(long orderId, long ownerId, long totalCents) implements OrderEvent {
}
