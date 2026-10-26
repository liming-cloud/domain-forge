package org.domainforge.iam.application.command.account;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.operation.AdministrationGuard;
import org.domainforge.iam.domain.model.Account;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 管理命令协调有限权限、当前资格和最后完整管理者，不把跨账号规则塞进聚合。
 *
 * <p>每个命令是一个数据库事务：先取管理守卫，再在事务内重新核对操作者资格，最后锁目标账号；
 * 业务变更与审计一起提交，任一步失败整体回滚。
 */
@Service
public class ManageAccount {
    private final AdministrationGuard guard;
    private final CurrentAuthorization authorization;
    private final AccountRepository accounts;
    private final AuditTrail audit;

    /**
     * 锁顺序固定为管理守卫、目标账号。
     */
    public ManageAccount(
            AdministrationGuard guard,
            CurrentAuthorization authorization,
            AccountRepository accounts,
            AuditTrail audit) {
        this.guard = guard;
        this.authorization = authorization;
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * 整体替换目标账号的直接权限；未知码或操作者自身当前不持有的权限均不能分配。
     */
    @Transactional
    public CommandResult permissions(String tokenDigest, long target, long version, Set<String> permissions) {
        validateTarget(target, version);
        if (permissions == null || !Account.MANAGEMENT_PERMISSIONS.containsAll(permissions))
            throw new IamException(IamFailure.INVALID_REQUEST, "权限不在目录中");
        Set<String> desired = Set.copyOf(permissions);
        guard.lock();
        var actor = authorization.require(tokenDigest, "iam:grant:manage");
        if (!actor.permissions().containsAll(desired))
            throw new IamException(IamFailure.FORBIDDEN, "不能分配自身未持有的权限");
        Account current = lockAccount(target, version);
        return save(current, current.changePermissions(desired), actor.accountId(), "account.permissions");
    }

    /**
     * 启用或禁用账号；状态变化提升凭据版本，旧会话失效，重新启用也不能恢复旧会话。
     */
    @Transactional
    public CommandResult state(String tokenDigest, long target, long version, boolean enabled) {
        validateTarget(target, version);
        guard.lock();
        var actor = authorization.require(tokenDigest, "iam:account:state");
        Account current = lockAccount(target, version);
        return save(current, current.changeEnabled(enabled), actor.accountId(), "account.state");
    }

    private static void validateTarget(long target, long version) {
        if (target <= 0 || version < 1) throw new IamException(IamFailure.INVALID_REQUEST, "目标账号与版本无效");
    }

    private Account lockAccount(long target, long version) {
        Account account = accounts.lock(target);
        if (account == null || account.version() != version)
            throw new IamException(IamFailure.CONFLICT, "账号不存在或版本已变化");
        return account;
    }

    /**
     * 保存变更并在同一事务写审计；变更会使最后一个完整管理者失去资格时拒绝。
     */
    private CommandResult save(Account current, Account changed, long actor, String action) {
        if (current.isCompleteAdministrator()
                && !changed.isCompleteAdministrator()
                && accounts.completeAdministrators() <= 1) {
            throw new IamException(IamFailure.CONFLICT, "必须保留至少一个完整管理者");
        }
        if (changed != current) {
            accounts.update(changed, current.version());
            audit.record(new AuditEvent(Long.toString(actor), changed.id(), action));
        }
        return new CommandResult(changed.id(), changed.version());
    }
}
