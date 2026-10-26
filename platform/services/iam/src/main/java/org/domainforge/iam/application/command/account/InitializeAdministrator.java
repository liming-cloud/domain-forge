package org.domainforge.iam.application.command.account;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.LoginProtection;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.application.port.operation.AdministrationGuard;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.port.operation.Transactions;
import org.domainforge.iam.domain.model.Account;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 仅受控部署命令可调用的初始管理主体用例，不开放HTTP也不提供重置后门。
 */
@Service
public final class InitializeAdministrator {
    private final Transactions transactions;
    private final AdministrationGuard guard;
    private final AccountRepository accounts;
    private final Passwords passwords;
    private final AuditTrail audit;
    private final LoginProtection protection;

    /**
     * 密码适配在事务外计算，管理守卫与账号始终使用同一个数据库。
     */
    public InitializeAdministrator(
            Transactions transactions,
            AdministrationGuard guard,
            AccountRepository accounts,
            Passwords passwords,
            AuditTrail audit,
            LoginProtection protection) {
        this.transactions = transactions;
        this.guard = guard;
        this.accounts = accounts;
        this.passwords = passwords;
        this.audit = audit;
        this.protection = protection;
    }

    /**
     * 首次创建唯一管理者；重复和并发落败均拒绝，不更改已有凭据或权限。
     */
    public CommandResult execute(String loginName, String password) {
        String name = Account.normalizeLoginName(loginName);
        String digest = passwords.encode(password);
        UUID operation = UUID.randomUUID();
        CommandResult result =
                transactions.execute(
                        () -> {
                            if (guard.lock()) throw new IamException(IamFailure.CONFLICT, "项目已经初始化");
                            Account account =
                                    Account.register(UUID.randomUUID(), name, digest)
                                            .changePermissions(Account.MANAGEMENT_PERMISSIONS);
                            accounts.insert(account);
                            protection.initialize();
                            guard.markInitialized();
                            return new CommandResult(
                                    operation, account.id(), account.version(), "administrator.initialize");
                        });
        audit.record(
                new AuditEvent(
                        operation, "deployment-command", result.targetId(), result.action(), "completed"));
        return result;
    }
}
