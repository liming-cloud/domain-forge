package org.domainforge.iam.application.command.account;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.AdministrationGuard;
import org.domainforge.iam.application.port.operation.CommandReceipts;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.port.operation.Transactions;
import org.domainforge.iam.domain.model.Account;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Service;

/** 管理命令协调有限权限、当前资格和最后完整管理者，不把跨账号规则塞进聚合。 */
@Service
public final class ManageAccount {
  private final Transactions transactions;
  private final AdministrationGuard guard;
  private final CurrentAuthorization authorization;
  private final AccountRepository accounts;
  private final CommandReceipts receipts;
  private final SecretOperations secrets;
  private final AuditTrail audit;

  /** 共享同一管理守卫，锁顺序固定为守卫、收据、目标账号。 */
  public ManageAccount(
      Transactions transactions,
      AdministrationGuard guard,
      CurrentAuthorization authorization,
      AccountRepository accounts,
      CommandReceipts receipts,
      SecretOperations secrets,
      AuditTrail audit) {
    this.transactions = transactions;
    this.guard = guard;
    this.authorization = authorization;
    this.accounts = accounts;
    this.receipts = receipts;
    this.secrets = secrets;
    this.audit = audit;
  }

  /** 分配整个明确集合；未知码或自身当前不持有的权限均不能分配。 */
  public CommandResult permissions(
      String tokenDigest, UUID operation, UUID target, long version, Set<String> permissions) {
    validateIdentity(operation, target, version);
    if (permissions == null || !Account.MANAGEMENT_PERMISSIONS.containsAll(permissions))
      throw new IamException(IamFailure.INVALID_REQUEST, "权限不在目录中");
    Set<String> desired = Set.copyOf(permissions);
    String action = "account.permissions";
    String fingerprint =
        secrets.fingerprint(
            action,
            target.toString(),
            Long.toString(version),
            String.join(",", new TreeSet<>(desired)));
    Change committed =
        transactions.execute(
            () -> {
              guard.lock();
              var actor = authorization.require(tokenDigest, "iam:grant:manage");
              if (!actor.permissions().containsAll(desired))
                throw new IamException(IamFailure.FORBIDDEN, "不能分配自身未持有的权限");
              CommandResult previous =
                  receipts.previous("account:" + actor.accountId(), operation, fingerprint);
              if (previous != null) return new Change(previous, null);
              Account current = lockAccount(target, version);
              Account changed = current.changePermissions(desired);
              return saveChange(
                  current,
                  changed,
                  actor.accountId(),
                  fingerprint,
                  new CommandResult(operation, target, changed.version(), action));
            });
    return completed(committed);
  }

  /** 状态变更提升凭据版本；重新启用不能恢复旧会话。 */
  public CommandResult state(
      String tokenDigest, UUID operation, UUID target, long version, boolean enabled) {
    validateIdentity(operation, target, version);
    String action = "account.state";
    String fingerprint =
        secrets.fingerprint(
            action, target.toString(), Long.toString(version), Boolean.toString(enabled));
    Change committed =
        transactions.execute(
            () -> {
              guard.lock();
              var actor = authorization.require(tokenDigest, "iam:account:state");
              CommandResult previous =
                  receipts.previous("account:" + actor.accountId(), operation, fingerprint);
              if (previous != null) return new Change(previous, null);
              Account current = lockAccount(target, version);
              Account changed = current.changeEnabled(enabled);
              return saveChange(
                  current,
                  changed,
                  actor.accountId(),
                  fingerprint,
                  new CommandResult(operation, target, changed.version(), action));
            });
    return completed(committed);
  }

  private void validateIdentity(UUID operation, UUID target, long version) {
    if (operation == null || target == null || version < 1)
      throw new IamException(IamFailure.INVALID_REQUEST, "命令身份与版本无效");
  }

  private Account lockAccount(UUID target, long version) {
    Account account = accounts.lock(target);
    if (account == null || account.version() != version)
      throw new IamException(IamFailure.CONFLICT, "账号不存在或版本已变化");
    return account;
  }

  /** 两条命令共享持久化责任；权限与业务状态转换在各自用例中显式完成。 */
  private Change saveChange(
      Account current, Account changed, UUID actor, String fingerprint, CommandResult result) {
    if (current.isCompleteAdministrator()
        && !changed.isCompleteAdministrator()
        && accounts.completeAdministrators() <= 1) {
      throw new IamException(IamFailure.CONFLICT, "必须保留至少一个完整管理者");
    }
    accounts.update(changed, current.version());
    receipts.save("account:" + actor, fingerprint, result);
    return new Change(result, actor);
  }

  /** 事务确认返回后才记录实际变更；重放保留原事实，不再次发送完成审计。 */
  private CommandResult completed(Change committed) {
    CommandResult result = committed.result();
    if (committed.actor() != null)
      audit.record(
          new AuditEvent(
              result.operationId(),
              committed.actor().toString(),
              result.targetId(),
              result.action(),
              "completed"));
    return result;
  }

  /** actor来自事务内当前授权；空actor表示已存在的幂等结果。 */
  private record Change(CommandResult result, UUID actor) {}
}
