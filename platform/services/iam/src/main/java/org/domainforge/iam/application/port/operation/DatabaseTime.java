package org.domainforge.iam.application.port.operation;

import java.time.Instant;

/**
 * 获取主库权威时间，供会话和邀请期限使用，不承担管理锁职责。
 */
public interface DatabaseTime {
    /**
     * 返回本次数据库语句执行时刻；数据库不可用时传播依赖失败。
     */
    Instant now();
}
