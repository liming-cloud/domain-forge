package org.domainforge.iam.infrastructure.persistence.retention;

/**
 * 每表每次最多删除 1000 行，数据库时钟决定保留期，其他实例跳过已锁行。
 */
@org.apache.ibatis.annotations.Mapper
public interface RetentionMapper {
    /**
     * 单语句上限，避免清理占满业务数据库连接。
     */
    void budget();

    /**
     * 删除过期 SQL 许可不影响任何有效会话，Redis 过期由原生 TTL 管理。
     */
    int sessions();

    /**
     * 已过期 24 小时以上的邀请。
     */
    int invitations();

    /**
     * 发生时间早于 90 天的审计。
     */
    int audits();
}
