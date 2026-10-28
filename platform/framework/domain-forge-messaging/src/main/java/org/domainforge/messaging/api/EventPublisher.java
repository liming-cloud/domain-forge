package org.domainforge.messaging.api;

/**
 * 同事务 Outbox 发布：在业务事务内写入待发送记录，提交后由中继发送；业务回滚时事件一并消失。
 *
 * <p>投递为至少一次：中继可能在发送成功、记录结果之前中断，同一事件会再次发送（事件 ID 不变）。
 */
public interface EventPublisher {
    /**
     * 记录要发布的事件。
     *
     * @param event 事件
     * @return 事件 ID（稳定，重发不变）
     * @throws IllegalStateException 当前没有进行中的事务
     */
    String publish(OutgoingEvent event);
}
