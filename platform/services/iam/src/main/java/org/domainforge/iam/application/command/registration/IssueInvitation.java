package org.domainforge.iam.application.command.registration;

import java.time.Duration;
import java.util.UUID;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.CommandReceipts;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.port.operation.DatabaseTime;
import org.domainforge.iam.application.port.operation.Transactions;
import org.domainforge.iam.domain.model.Account;
import org.domainforge.iam.domain.model.RegistrationInvitation;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.domain.repository.InvitationRepository;
import org.springframework.stereotype.Service;

/** 邀请创建和显式重发用例，秘密不进入收据；已消费邀请不可借重发重置账号。 */
@Service
public final class IssueInvitation {
  private final Transactions transactions;
  private final DatabaseTime clock;
  private final CurrentAuthorization authorization;
  private final AccountRepository accounts;
  private final InvitationRepository invitations;
  private final CommandReceipts receipts;
  private final SecretOperations secrets;
  private final AuditTrail audit;

  /** 事务内重新读取当前授权，邀请与收据仍由各自数据库锁协调。 */
  public IssueInvitation(
      Transactions transactions,
      DatabaseTime clock,
      CurrentAuthorization authorization,
      AccountRepository accounts,
      InvitationRepository invitations,
      CommandReceipts receipts,
      SecretOperations secrets,
      AuditTrail audit) {
    this.transactions = transactions;
    this.clock = clock;
    this.authorization = authorization;
    this.accounts = accounts;
    this.invitations = invitations;
    this.receipts = receipts;
    this.secrets = secrets;
    this.audit = audit;
  }

  /** replacement为空表示新邀请，否则原邀请须尚未消费；有效期固定24小时。 */
  public InvitationIssued execute(
      String tokenDigest, UUID operation, String loginName, UUID replacement) {
    if (operation == null) throw new IamException(IamFailure.INVALID_REQUEST, "缺少操作ID");
    String name = Account.normalizeLoginName(loginName);
    String fingerprint = secrets.fingerprint("invitation.issue", name, String.valueOf(replacement));
    Issuance committed =
        transactions.execute(() -> issue(tokenDigest, operation, name, replacement, fingerprint));
    if (committed.event() != null) audit.record(committed.event());
    return committed.issued();
  }

  /** 当前授权和幂等核对先于实际变更，重放只返回已创建事实而不重交秘密。 */
  private Issuance issue(
      String tokenDigest, UUID operation, String name, UUID replacement, String fingerprint) {
    CurrentIdentity actor = authorization.require(tokenDigest, "iam:invitation:create");
    String scope = "account:" + actor.accountId();
    CommandResult previous = receipts.previous(scope, operation, fingerprint);
    if (previous != null) return new Issuance(new InvitationIssued(previous, null), null);
    if (accounts.findByLoginName(name) != null)
      throw new IamException(IamFailure.CONFLICT, "该登录名已不可邀请");
    if (replacement != null) invalidate(replacement, name);
    String secret = secrets.newSecret();
    RegistrationInvitation invitation =
        new RegistrationInvitation(
            UUID.randomUUID(),
            name,
            secrets.digest(secret),
            clock.now().plus(Duration.ofHours(24)),
            actor.accountId(),
            null,
            false);
    invitations.insert(invitation);
    CommandResult result = new CommandResult(operation, invitation.id(), 1, "invitation.issue");
    receipts.save(scope, fingerprint, result);
    AuditEvent event =
        new AuditEvent(
            operation,
            actor.accountId().toString(),
            result.targetId(),
            result.action(),
            "completed");
    return new Issuance(new InvitationIssued(result, secret), event);
  }

  /** 重发不能换绑登录名，消费与重发竞争只有一个转换可提交。 */
  private void invalidate(UUID id, String name) {
    RegistrationInvitation original = invitations.lock(id);
    if (original == null || !original.loginName().equals(name))
      throw new IamException(IamFailure.CONFLICT, "原邀请不可重发");
    try {
      invitations.finish(original.invalidate());
    } catch (IllegalStateException e) {
      throw new IamException(IamFailure.CONFLICT, "原邀请不可重发");
    }
  }

  /** 审计事实随本次新写入产生；重放不重交秘密，也不创建新的完成事件。 */
  private record Issuance(InvitationIssued issued, AuditEvent event) {}
}
