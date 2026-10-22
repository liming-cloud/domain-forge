package org.domainforge.iam.infrastructure.persistence.invitation;

import java.time.Instant;
import java.util.UUID;
import org.domainforge.iam.domain.model.RegistrationInvitation;

/** 邀请存储状态与领域分离，公开接口不返回本对象。 */
public record InvitationRow(
    String id,
    String loginName,
    String secretDigest,
    Instant expiresAt,
    String createdBy,
    String consumedBy,
    boolean invalidated) {
  /** 将邀请身份转换为驱动可绑定的UUID字符串。 */
  static InvitationRow from(RegistrationInvitation invitation) {
    return new InvitationRow(
        invitation.id().toString(),
        invitation.loginName(),
        invitation.secretDigest(),
        invitation.expiresAt(),
        invitation.createdBy().toString(),
        invitation.consumedBy() == null ? null : invitation.consumedBy().toString(),
        invitation.invalidated());
  }

  /** 恢复领域状态，消费身份为空表示尚未消费。 */
  RegistrationInvitation toDomain() {
    return new RegistrationInvitation(
        UUID.fromString(id),
        loginName,
        secretDigest,
        expiresAt,
        UUID.fromString(createdBy),
        consumedBy == null ? null : UUID.fromString(consumedBy),
        invalidated);
  }

  /** 诊断表示不携带秘密摘要。 */
  @Override
  public String toString() {
    return "InvitationRow[id=" + id + "]";
  }
}
