package org.domainforge.iam.application.command.audit;

import java.util.TreeSet;
import java.util.UUID;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.audit.ManualAudit;
import org.domainforge.iam.application.port.audit.ManualAuditStore;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.CommandReceipts;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.port.operation.DatabaseTime;
import org.domainforge.iam.application.port.operation.Transactions;
import org.springframework.stereotype.Service;

/** 审计补录是显式受保护命令，不把普通审计失败时的缺失事实自动猜成成功。 */
@Service
public final class BackfillAudit {
  private final Transactions transactions;
  private final DatabaseTime clock;
  private final CurrentAuthorization authorization;
  private final CommandReceipts receipts;
  private final ManualAuditStore store;
  private final SecretOperations secrets;
  private final AuditTrail audit;

  /** 补录内容、幂等结果在同一数据库事务中保存，完成后另记补录操作本身。 */
  public BackfillAudit(
      Transactions transactions,
      DatabaseTime clock,
      CurrentAuthorization authorization,
      CommandReceipts receipts,
      ManualAuditStore store,
      SecretOperations secrets,
      AuditTrail audit) {
    this.transactions = transactions;
    this.clock = clock;
    this.authorization = authorization;
    this.receipts = receipts;
    this.store = store;
    this.secrets = secrets;
    this.audit = audit;
  }

  /** 原操作者和原结果保持缺失，可信actor仅表示本次补录者；不宣称完整恢复。 */
  public CommandResult execute(String tokenDigest, UUID operation, ManualAudit entry) {
    if (operation == null || entry == null)
      throw new IamException(IamFailure.INVALID_REQUEST, "缺少补录操作信息");
    String fingerprint =
        secrets.fingerprint(
            "audit.backfill",
            entry.originalOperationId().toString(),
            entry.targetId().toString(),
            entry.action(),
            String.valueOf(entry.occurredAt()),
            entry.provenance(),
            String.join(",", new TreeSet<>(entry.missingFields())));
    Backfill committed =
        transactions.execute(
            () -> {
              var actor = authorization.require(tokenDigest, "iam:audit:read");
              String scope = "account:" + actor.accountId();
              var previous = receipts.previous(scope, operation, fingerprint);
              if (previous != null) return new Backfill(previous, null);
              if (entry.occurredAt() != null && entry.occurredAt().isAfter(clock.now()))
                throw new IamException(IamFailure.INVALID_REQUEST, "原发生时间不能在未来");
              store.append(actor.accountId(), entry);
              var result = new CommandResult(operation, entry.targetId(), 1, "audit.backfill");
              receipts.save(scope, fingerprint, result);
              return new Backfill(
                  result,
                  new AuditEvent(
                      operation,
                      actor.accountId().toString(),
                      entry.targetId(),
                      "audit.backfill",
                      "completed"));
            });
    if (committed.event() != null) audit.record(committed.event());
    return committed.result();
  }

  /** 幂等重放不表示又发生一次补录；可信操作者在事务内确定。 */
  private record Backfill(CommandResult result, AuditEvent event) {}
}
