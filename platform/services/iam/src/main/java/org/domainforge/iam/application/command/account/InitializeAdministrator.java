package org.domainforge.iam.application.command.account;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.application.port.operation.AdministrationGuard;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.domainforge.iam.domain.model.Account;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 仅受控部署命令可调用的初始管理主体用例，不开放HTTP也不提供重置后门。
 */
@Service
public final class InitializeAdministrator {
    private final TransactionOperations transactions;
    private final AdministrationGuard guard;
    private final AccountRepository accounts;
    private final Identifiers identifiers;
    private final Passwords passwords;
    private final AuditTrail audit;

    /**
     * 密码摘要在事务外计算，管理守卫、账号与审计在同一个数据库事务中提交。
     */
    public InitializeAdministrator(
            TransactionOperations transactions,
            AdministrationGuard guard,
            AccountRepository accounts,
            Identifiers identifiers,
            Passwords passwords,
            AuditTrail audit) {
        this.transactions = transactions;
        this.guard = guard;
        this.accounts = accounts;
        this.identifiers = identifiers;
        this.passwords = passwords;
        this.audit = audit;
    }

    /**
     * 首次创建唯一管理者并返回其账号 ID；重复和并发落败均拒绝，不更改已有凭据或权限。
     */
    public long execute(String loginName, String password) {
        String name = Account.normalizeLoginName(loginName);
        String digest = passwords.encode(password);
        Long created = transactions.execute(status -> {
            if (guard.lock()) throw new IamException(IamFailure.CONFLICT, "项目已经初始化");
            Account account = Account.register(identifiers.next(), name, digest)
                    .changePermissions(Account.MANAGEMENT_PERMISSIONS);
            accounts.insert(account);
            guard.markInitialized();
            audit.record(new AuditEvent("deployment-command", account.id(), "administrator.initialize"));
            return account.id();
        });
        return created;
    }
}
