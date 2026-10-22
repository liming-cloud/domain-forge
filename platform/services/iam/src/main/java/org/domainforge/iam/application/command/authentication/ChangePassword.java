package org.domainforge.iam.application.command.authentication;

import java.util.UUID;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.CommandReceipts;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.port.operation.Transactions;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Service;

/** 自助改密需要当前会话和旧密码，SQL凭据版本提交即使全部旧许可失效。 */
@Service
public final class ChangePassword {
  private final Transactions transactions;
  private final CurrentAuthorization authorization;
  private final AccountRepository accounts;
  private final CommandReceipts receipts;
  private final Passwords passwords;
  private final SecretOperations secrets;
  private final AuditTrail audit;

  /** 密码验证和新摘要计算在长事务之外，提交前重新检查账号版本。 */
  public ChangePassword(
      Transactions transactions,
      CurrentAuthorization authorization,
      AccountRepository accounts,
      CommandReceipts receipts,
      Passwords passwords,
      SecretOperations secrets,
      AuditTrail audit) {
    this.transactions = transactions;
    this.authorization = authorization;
    this.accounts = accounts;
    this.receipts = receipts;
    this.passwords = passwords;
    this.secrets = secrets;
    this.audit = audit;
  }

  /** 结果不返回新凭据；旧会话失效后不能依靠操作ID重读结果绕过当前身份检查。 */
  public CommandResult execute(
      CurrentIdentity identity, UUID operation, String oldPassword, String newPassword) {
    if (operation == null) throw new IamException(IamFailure.INVALID_REQUEST, "缺少操作ID");
    var verified = accounts.find(identity.accountId());
    if (verified == null || !passwords.matches(oldPassword, verified.credentialDigest()))
      throw new IamException(IamFailure.UNAUTHENTICATED, "当前凭据无效");
    String digest = passwords.encode(newPassword);
    String fingerprint = secrets.fingerprint("account.password", oldPassword, newPassword);
    PasswordChange committed =
        transactions.execute(
            () -> {
              var actor = authorization.require(identity.tokenDigest(), null);
              String scope = "account:" + actor.accountId();
              CommandResult previous = receipts.previous(scope, operation, fingerprint);
              if (previous != null) return new PasswordChange(previous, null);
              var current = accounts.lock(actor.accountId());
              if (current.credentialVersion() != verified.credentialVersion())
                throw new IamException(IamFailure.CONFLICT, "凭据已变化，请重新登录");
              var changed = current.changePassword(digest);
              accounts.update(changed, current.version());
              var result =
                  new CommandResult(operation, current.id(), changed.version(), "account.password");
              receipts.save(scope, fingerprint, result);
              return new PasswordChange(
                  result,
                  new AuditEvent(
                      operation,
                      actor.accountId().toString(),
                      current.id(),
                      result.action(),
                      "completed"));
            });
    if (committed.event() != null) audit.record(committed.event());
    return committed.result();
  }

  /** 提交后审计使用事务内确认的操作者和新变更事实。 */
  private record PasswordChange(CommandResult result, AuditEvent event) {}
}
