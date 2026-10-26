package org.domainforge.iam.domain.repository;

import org.domainforge.iam.domain.model.RegistrationInvitation;

/**
 * 注册邀请仓储；消费邀请与创建账号必须参与同一业务事务。
 */
public interface InvitationRepository {
    /**
     * 按高熵秘密的摘要锁定邀请，不存在返回null。
     */
    RegistrationInvitation lockByDigest(String digest);

    /**
     * 按管理操作指定身份锁定邀请，不存在返回null。
     */
    RegistrationInvitation lock(long id);

    /**
     * 保存新邀请，不保存或记录原始秘密。
     */
    void insert(RegistrationInvitation invitation);

    /**
     * 保存消费或失效终态；调用方必须先锁定仍有效的原状态。
     */
    void finish(RegistrationInvitation invitation);
}
