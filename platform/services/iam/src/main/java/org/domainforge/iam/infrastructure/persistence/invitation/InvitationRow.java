package org.domainforge.iam.infrastructure.persistence.invitation;

import org.domainforge.iam.domain.model.RegistrationInvitation;

import java.time.Instant;

/**
 * 邀请存储状态与领域分离，公开接口不返回本对象。
 */
public record InvitationRow(
        long id,
        String loginName,
        String secretDigest,
        Instant expiresAt,
        long createdBy,
        Long consumedBy,
        boolean invalidated) {
    /**
     * 将领域邀请转换为存储表示。
     */
    static InvitationRow from(RegistrationInvitation invitation) {
        return new InvitationRow(
                invitation.id(),
                invitation.loginName(),
                invitation.secretDigest(),
                invitation.expiresAt(),
                invitation.createdBy(),
                invitation.consumedBy(),
                invitation.invalidated());
    }

    /**
     * 恢复领域状态，消费身份为空表示尚未消费。
     */
    RegistrationInvitation toDomain() {
        return new RegistrationInvitation(
                id,
                loginName,
                secretDigest,
                expiresAt,
                createdBy,
                consumedBy,
                invalidated);
    }

    /**
     * 诊断表示不携带秘密摘要。
     */
    @Override
    public String toString() {
        return "InvitationRow[id=" + id + "]";
    }
}
