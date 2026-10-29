package org.domainforge.messaging.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.domainforge.messaging.api.OutgoingEvent;

import java.util.List;

/**
 * Outbox 的读写；SQL 在 mapper/domain-forge/messaging/OutboxMapper.xml。
 */
@Mapper
public interface OutboxMapper {
    /**
     * 写入一条待发送记录。
     */
    int insert(@Param("event") OutgoingEvent event, @Param("eventId") String eventId,
               @Param("traceParent") String traceParent);

    /**
     * 抢占到期的记录（FOR UPDATE SKIP LOCKED）；有顺序键的只取该键最早的一条未发送记录。
     */
    List<OutboxRow> claim(@Param("limit") int limit);

    /**
     * 记录发送成功。
     */
    int markSent(@Param("id") long id);

    /**
     * 记录发送失败与下次尝试时间。
     */
    int markFailed(@Param("id") long id, @Param("delaySeconds") long delaySeconds, @Param("error") String error);

    /**
     * 删除早于保留期的已发送记录，返回删除条数。
     */
    int purgeSent(@Param("retentionHours") long retentionHours, @Param("limit") int limit);

    /**
     * 未发送记录数，供积压指标使用。
     */
    long pending();
}
