package org.domainforge.messaging.api;

/**
 * 集成事件的处理者。每个实现对应一个消费组订阅一个主题，由组件在本地事务内调用：
 * 去重记录与 {@link #handle} 的写入一起提交，同一事件在去重保留期内只生效一次。
 *
 * <p>抛出异常则事务回滚、消息稍后重投；超过服务端的最大重试次数后进入死信并告警，不会被丢弃为成功。
 * 处理中不要调用不可回滚的外部副作用；需要时另行设计幂等键或状态核对。
 */
public interface EventHandler {
    /**
     * 订阅的主题。
     */
    String topic();

    /**
     * 消费组；须已在 RocketMQ 中创建（顺序主题对应顺序消费组）。
     */
    String group();

    /**
     * 处理事件；在本地事务内执行。
     */
    void handle(ReceivedEvent event);
}
