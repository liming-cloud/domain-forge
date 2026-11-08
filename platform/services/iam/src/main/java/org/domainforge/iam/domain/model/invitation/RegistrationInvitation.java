package org.domainforge.iam.domain.model.invitation;

import org.domainforge.iam.domain.IamRuleViolation;
import org.domainforge.iam.domain.model.shared.LoginName;
import org.domainforge.shared.AggregateRoot;

import java.time.Instant;

/**
 * 注册邀请只允许绑定的登录名在有效期内消费一次；秘密仅以摘要保存。
 */
public record RegistrationInvitation(
        long id,
        String loginName,
        String secretDigest,
        Instant expiresAt,
        long createdBy,
        Long consumedBy,
        boolean invalidated)
        implements AggregateRoot {
    /**
     * 恢复状态也校验必要身份、秘密摘要和互斥的终态。
     */
    public RegistrationInvitation {
        if (id <= 0
                || createdBy <= 0
                || expiresAt == null
                || secretDigest == null
                || !secretDigest.matches("[a-f0-9]{64}")
                || (consumedBy != null && (consumedBy <= 0 || invalidated))) {
            throw IamRuleViolation.invalid("INVITATION_STATE_INVALID", "邀请状态无效");
        }
        loginName = new LoginName(loginName).value();
    }

    /**
     * 消费后的状态由调用方与新账号在同一事务中保存。
     */
    public RegistrationInvitation consume(long accountId, Instant now) {
        if (accountId <= 0 || now == null) throw IamRuleViolation.invalid("INVITATION_CONSUMER_INVALID", "消费身份与时间不可为空");
        if (consumedBy != null || invalidated || !now.isBefore(expiresAt)) {
            throw IamRuleViolation.conflict("INVITATION_UNUSABLE", "邀请无效或已失效");
        }
        return new RegistrationInvitation(
                id, loginName, secretDigest, expiresAt, createdBy, accountId, false);
    }

    /**
     * 重发前使旧邀请失效；已消费邀请不能用于重置账号。
     */
    public RegistrationInvitation invalidate() {
        if (consumedBy != null || invalidated) throw IamRuleViolation.conflict("INVITATION_UNUSABLE", "邀请已消费或失效");
        return new RegistrationInvitation(
                id, loginName, secretDigest, expiresAt, createdBy, null, true);
    }

    /**
     * 诊断表示不携带秘密摘要。
     */
    @Override
    public String toString() {
        return "RegistrationInvitation[id=" + id + "]";
    }
}
