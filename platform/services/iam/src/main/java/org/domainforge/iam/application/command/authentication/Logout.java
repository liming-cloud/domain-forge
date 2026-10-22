package org.domainforge.iam.application.command.authentication;

import java.util.UUID;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.NativeSessions;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.authentication.SessionStore;
import org.domainforge.iam.application.port.operation.CommandReceipts;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.port.operation.Transactions;
import org.springframework.stereotype.Service;

/** 当前会话退出先提交SQL撤销，再尽力清理Redis；其他登录会话保持有效。 */
@Service
public final class Logout {
  private final Transactions transactions;
  private final CurrentAuthorization authorization;
  private final CommandReceipts receipts;
  private final SecretOperations secrets;
  private final SessionStore sessions;
  private final NativeSessions nativeSessions;
  private final AuditTrail audit;

  /** 会话清理不承担安全生效，SQL许可始终是必要条件。 */
  public Logout(
      Transactions transactions,
      CurrentAuthorization authorization,
      CommandReceipts receipts,
      SecretOperations secrets,
      SessionStore sessions,
      NativeSessions nativeSessions,
      AuditTrail audit) {
    this.transactions = transactions;
    this.authorization = authorization;
    this.receipts = receipts;
    this.secrets = secrets;
    this.sessions = sessions;
    this.nativeSessions = nativeSessions;
    this.audit = audit;
  }

  /** token必须是入口原生解析的当前令牌，不能由请求体指定他人令牌。 */
  public CommandResult execute(CurrentIdentity identity, String token, UUID operation) {
    if (operation == null || !identity.tokenDigest().equals(secrets.digest(token)))
      throw new IamException(IamFailure.INVALID_REQUEST, "退出输入无效");
    String fingerprint = secrets.fingerprint("session.logout", identity.tokenDigest());
    CommandResult result =
        transactions.execute(
            () -> {
              var actor = authorization.require(identity.tokenDigest(), null);
              String scope = "account:" + actor.accountId();
              CommandResult previous = receipts.previous(scope, operation, fingerprint);
              if (previous != null) return previous;
              sessions.revoke(identity.tokenDigest());
              var completed =
                  new CommandResult(
                      operation, actor.accountId(), actor.credentialVersion(), "session.logout");
              receipts.save(scope, fingerprint, completed);
              return completed;
            });
    nativeSessions.cleanup(token);
    audit.record(
        new AuditEvent(
            operation,
            identity.accountId().toString(),
            result.targetId(),
            result.action(),
            "completed"));
    return result;
  }
}
