package org.domainforge.iam.infrastructure.persistence.fixture;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 集成测试专用：重置数据、观察真实表状态，并安装数据库级故障（触发器）以验证事务回滚。
 */
@Mapper
public interface IdentityFixtureMapper {
    /**
     * 清空业务表并把管理守卫恢复为未初始化。
     */
    void reset();

    /**
     * 让 iam_session_grant 的插入在 COMMIT 时失败（延迟约束触发器）。
     */
    void installGrantCommitFailure();

    /**
     * 移除会话许可提交故障。
     */
    void removeGrantCommitFailure();

    /**
     * 让 iam_audit 的插入立即失败。
     */
    void installAuditFailure();

    /**
     * 移除审计写入故障。
     */
    void removeAuditFailure();

    /**
     * 指定登录名的账号数。
     */
    int accountCount(@Param("name") String name);

    /**
     * 指定令牌摘要的会话许可数。
     */
    int sessionCount(@Param("digest") String digest);

    /**
     * 指定动作的审计数。
     */
    int auditCount(@Param("action") String action);

    /**
     * 使指定登录名的邀请过期。
     */
    void expireInvitation(@Param("name") String name);

    /**
     * 插入指定数量、发生时间早于保留期的审计，动作名为 retention.old。
     */
    void insertExpiredAudits(@Param("count") int count);
}
