package org.domainforge.messaging.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * 消息集成测试读写 Outbox 与消费效果表（SQL 在 mapper/messaging-it/MessagingFixtureMapper.xml）。与 OutboxMapper 同包，
 * 由 MessagingAutoConfiguration 的 Mapper 扫描注册。
 */
@Mapper
public interface MessagingFixtureMapper {
    /**
     * Outbox 中该事件的记录（列名为键，空值列不出现）；没有记录时为 null。
     */
    @Nullable
    Map<String, Object> outboxByEvent(String eventId);

    /**
     * Outbox 中该 id 的记录（列名为键，空值列不出现）。
     */
    Map<String, Object> outbox(long id);

    long idOf(String eventId);

    void clearOutbox();

    void markUnsent(String eventId);

    void expireLease(long id);

    /**
     * 以 FOR UPDATE NOWAIT 锁该行；行已被其他连接锁住时抛出 SQLState 55P03。
     */
    long lock(long id);

    void recordEffect(@Param("eventId") String eventId, @Param("handledBy") String handledBy);

    int effects(String eventId);
}
