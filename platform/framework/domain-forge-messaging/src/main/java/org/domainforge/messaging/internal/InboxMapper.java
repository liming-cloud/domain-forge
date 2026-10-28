package org.domainforge.messaging.internal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消费去重记录；SQL 在 mapper/domain-forge/messaging/InboxMapper.xml。
 */
@Mapper
public interface InboxMapper {
    /**
     * 登记收到的事件；已登记（重复投递）时返回 0。
     */
    int record(@Param("group") String group, @Param("eventId") String eventId);

    /**
     * 删除早于保留期的记录，返回删除条数。
     */
    int purge(@Param("retentionHours") long retentionHours, @Param("limit") int limit);
}
