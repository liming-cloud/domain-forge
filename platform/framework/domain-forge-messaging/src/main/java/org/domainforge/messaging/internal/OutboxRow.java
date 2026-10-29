package org.domainforge.messaging.internal;

/**
 * Outbox 中一条待发送记录。
 *
 * @param id        自增主键，决定同一顺序键内的发送顺序
 * @param eventId   事件 ID
 * @param topic     主题
 * @param eventType 事件类型
 * @param version   事件合同版本
 * @param eventKey  业务键
 * @param orderKey  顺序键，可为空
 * @param payload   JSON 文本
 * @param attempts    已失败次数
 * @param traceParent 发布时的链路上下文，可为空
 */
public record OutboxRow(
        long id,
        String eventId,
        String topic,
        String eventType,
        int version,
        String eventKey,
        String orderKey,
        String payload,
        int attempts,
        String traceParent) {
}
