package org.domainforge.iam.application.command.authentication;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 自助改密需要当前会话和旧密码；凭据版本提交即使该账号全部旧会话失效。
 */
@Service
public final class ChangePassword {
    private final TransactionOperations transactions;
    private final CurrentAuthorization authorization;
    private final AccountRepository accounts;
    private final Passwords passwords;
    private final AuditTrail audit;

    /**
     * 旧密码校验和新摘要计算在事务之外，提交前重新检查凭据版本。
     */
    public ChangePassword(
            TransactionOperations transactions,
            CurrentAuthorization authorization,
            AccountRepository accounts,
            Passwords passwords,
            AuditTrail audit) {
        this.transactions = transactions;
        this.authorization = authorization;
        this.accounts = accounts;
        this.passwords = passwords;
        this.audit = audit;
    }

    /**
     * 返回账号及其新版本，不返回凭据；客户端须用新密码重新登录。
     */
    public CommandResult execute(CurrentIdentity identity, String oldPassword, String newPassword) {
        var verified = accounts.find(identity.accountId());
        if (verified == null || !passwords.matches(oldPassword, verified.credentialDigest()))
            throw new IamException(IamFailure.UNAUTHENTICATED, "当前凭据无效");
        String digest = passwords.encode(newPassword);
        return transactions.execute(status -> {
            var actor = authorization.require(identity.tokenDigest(), null);
            var current = accounts.lock(actor.accountId());
            if (current.credentialVersion() != verified.credentialVersion())
                throw new IamException(IamFailure.CONFLICT, "凭据已变化，请重新登录");
            var changed = current.changePassword(digest);
            accounts.update(changed, current.version());
            audit.record(new AuditEvent(Long.toString(actor.accountId()), current.id(), "account.password"));
            return new CommandResult(changed.id(), changed.version());
        });
    }
}
