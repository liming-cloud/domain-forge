package org.domainforge.iam.domain.model;

import java.time.Instant;
import java.util.UUID;
import org.domainforge.domain.api.AggregateRoot;

/** 注册邀请只允许绑定的登录名在有效期内消费一次；秘密仅以摘要保存。 */
public record RegistrationInvitation(
    UUID id,
    String loginName,
    String secretDigest,
    Instant expiresAt,
    UUID createdBy,
    UUID consumedBy,
    boolean invalidated)
    implements AggregateRoot {
  /** 恢复状态也校验必要身份、秘密摘要和互斥的终态。 */
  public RegistrationInvitation {
    if (id == null
        || createdBy == null
        || expiresAt == null
        || secretDigest == null
        || !secretDigest.matches("[a-f0-9]{64}")
        || (consumedBy != null && invalidated)) {
      throw new IllegalArgumentException("邀请状态无效");
    }
    loginName = Account.normalizeLoginName(loginName);
  }

  /** 消费后的状态由调用方与新账号在同一事务中保存。 */
  public RegistrationInvitation consume(UUID accountId, Instant now) {
    if (accountId == null || now == null) throw new IllegalArgumentException("消费身份与时间不可为空");
    if (consumedBy != null || invalidated || !now.isBefore(expiresAt)) {
      throw new IllegalStateException("邀请已失效或已消费");
    }
    return new RegistrationInvitation(
        id, loginName, secretDigest, expiresAt, createdBy, accountId, false);
  }

  /** 重发前使旧邀请失效；已消费邀请不能用于重置账号。 */
  public RegistrationInvitation invalidate() {
    if (consumedBy != null || invalidated) throw new IllegalStateException("邀请已消费或失效");
    return new RegistrationInvitation(
        id, loginName, secretDigest, expiresAt, createdBy, null, true);
  }

  /** 诊断表示不携带秘密摘要。 */
  @Override
  public String toString() {
    return "RegistrationInvitation[id=" + id + "]";
  }
}
