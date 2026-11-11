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
     * 认领到期且没有有效租约的记录，写入租约截止时间与令牌（FOR UPDATE SKIP LOCKED，随调用方的短事务提交）；
     * 有顺序键的只取该键最早的一条未发送记录。按 id 升序返回。
     */
    List<OutboxRow> claim(@Param("limit") int limit, @Param("token") String token,
            @Param("leaseSeconds") long leaseSeconds);

    /**
     * 记录发送成功并释放租约；令牌不符（租约已被其他实例接手）时不更新。
     */
    int markSent(@Param("id") long id, @Param("token") String token);

    /**
     * 记录发送失败与下次尝试时间并释放租约；令牌不符时不更新。
     */
    int markFailed(@Param("id") long id, @Param("token") String token, @Param("delaySeconds") long delaySeconds,
            @Param("error") String error);

    /**
     * 释放该令牌认领、仍未发送的记录，使其下一轮即可被认领。
     */
    int release(@Param("token") String token);

    /**
     * 删除早于保留期的已发送记录，返回删除条数。
     */
    int purgeSent(@Param("retentionHours") long retentionHours, @Param("limit") int limit);

    /**
     * 未发送记录数，供积压指标使用。
     */
    long pending();
}
