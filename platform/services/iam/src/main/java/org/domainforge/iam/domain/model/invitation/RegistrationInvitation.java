package org.domainforge.iam.domain.model.invitation;

import org.domainforge.iam.domain.IamRuleViolation;
import org.domainforge.iam.domain.model.shared.LoginName;
import org.domainforge.shared.AggregateRoot;

import java.time.Instant;

/**
 * 注册邀请是绑定登录名、有效期内可用的一次性凭据；秘密仅以摘要保存。是否已被使用由账号记录的邀请摘要推导，不在本聚合中保存。
 */
public record RegistrationInvitation(
        long id,
        String loginName,
        String secretDigest,
        Instant expiresAt,
        long createdBy,
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
                || !secretDigest.matches("[a-f0-9]{64}")) {
            throw IamRuleViolation.invalid("INVITATION_STATE_INVALID", "邀请状态无效");
        }
        loginName = new LoginName(loginName).value();
    }

    /**
     * 注册前校验：未失效且未到期；是否已被使用由调用方另行查询。
     */
    public void requireUsable(Instant now) {
        if (now == null) throw IamRuleViolation.invalid("INVITATION_TIME_INVALID", "校验时间不可为空");
        if (invalidated || !now.isBefore(expiresAt)) {
            throw IamRuleViolation.conflict("INVITATION_UNUSABLE", "邀请无效或已失效");
        }
    }

    /**
     * 重发前使旧邀请失效；调用方须先确认邀请未被使用。
     */
    public RegistrationInvitation invalidate() {
        if (invalidated) throw IamRuleViolation.conflict("INVITATION_UNUSABLE", "邀请已失效");
        return new RegistrationInvitation(
                id, loginName, secretDigest, expiresAt, createdBy, true);
    }

    /**
     * 诊断表示不携带秘密摘要。
     */
    @Override
    public String toString() {
        return "RegistrationInvitation[id=" + id + "]";
    }
}
