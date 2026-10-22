package org.domainforge.iam.application.port.authentication;

import java.time.Instant;
import java.util.UUID;
import org.domainforge.iam.domain.model.Account;

/** SQL会话许可只承担凭据版本和撤销事实，不复制原生Redis会话内部结构。 */
public record SessionGrant(
    String tokenDigest,
    UUID accountId,
    long credentialVersion,
    Instant expiresAt,
    boolean revoked) {
  /** 任何恢复状态都必须具备有效摘要、身份、正版本与明确期限。 */
  public SessionGrant {
    if (tokenDigest == null
        || !tokenDigest.matches("[a-f0-9]{64}")
        || accountId == null
        || credentialVersion < 1
        || expiresAt == null) throw new IllegalArgumentException("会话许可无效");
  }

  /** 退出只撤销该许可，不改变同账号其他会话。 */
  public SessionGrant revoke() {
    return new SessionGrant(tokenDigest, accountId, credentialVersion, expiresAt, true);
  }

  /** 判断SQL部分资格；仍须同时通过原生会话验证，时间由数据库提供。 */
  public boolean permits(Account account, Instant now) {
    return !revoked
        && now.isBefore(expiresAt)
        && accountId.equals(account.id())
        && account.enabled()
        && credentialVersion == account.credentialVersion();
  }

  /** 不将摘要记录到默认日志表示。 */
  @Override
  public String toString() {
    return "SessionGrant[accountId=" + accountId + "]";
  }
}
