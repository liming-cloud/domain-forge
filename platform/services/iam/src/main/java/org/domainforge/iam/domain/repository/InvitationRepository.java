package org.domainforge.iam.domain.repository;

import org.domainforge.iam.domain.model.invitation.RegistrationInvitation;
import org.jspecify.annotations.Nullable;

/**
 * 注册邀请仓储；邀请只作为一次性凭据被读取，使用事实记录在账号上。
 */
public interface InvitationRepository {
    /**
     * 按高熵秘密的摘要锁定邀请（与重发互斥），不存在返回null。
     */
    @Nullable
    RegistrationInvitation lockByDigest(String digest);

    /**
     * 按管理操作指定身份锁定邀请，不存在返回null。
     */
    @Nullable
    RegistrationInvitation lock(long id);

    /**
     * 保存新邀请，不保存或记录原始秘密。
     */
    void insert(RegistrationInvitation invitation);

    /**
     * 保存失效终态；调用方必须先锁定仍有效的原状态。
     */
    void invalidate(RegistrationInvitation invitation);
}
