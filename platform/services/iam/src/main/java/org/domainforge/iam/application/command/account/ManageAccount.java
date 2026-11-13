package org.domainforge.iam.application.command.account;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.command.ExpectedVersion;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.operation.AdministrationGuard;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.account.Membership;
import org.domainforge.iam.domain.model.role.Role;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.domain.repository.OrganizationRepository;
import org.domainforge.iam.domain.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 账号管理命令：启用状态、角色分配与成员归属；跨账号的“至少一名系统管理员”规则在此显式协调。
 *
 * <p>每个命令是一个数据库事务，锁顺序固定为：管理守卫（只在可能移除系统管理员的状态与角色命令中取得）→ 组织协调 → 账号 → 按 ID 排序的角色。
 * 业务变更与审计一起提交，任一步失败整体回滚。
 */
@Service
public class ManageAccount {
    private final AdministrationGuard guard;
    private final CurrentAuthorization authorization;
    private final AccountRepository accounts;
    private final RoleRepository roles;
    private final OrganizationRepository organizations;
    private final AuditTrail audit;

    /**
     * 只依赖仓储合同与审计端口。
     */
    public ManageAccount(
            AdministrationGuard guard,
            CurrentAuthorization authorization,
            AccountRepository accounts,
            RoleRepository roles,
            OrganizationRepository organizations,
            AuditTrail audit) {
        this.guard = guard;
        this.authorization = authorization;
        this.accounts = accounts;
        this.roles = roles;
        this.organizations = organizations;
        this.audit = audit;
    }

    /**
     * 启用或禁用账号；状态变化提升凭据版本，旧会话失效。非系统管理员不能修改系统管理员账号。
     */
    @Transactional
    public CommandResult state(String tokenDigest, long target, long version, boolean enabled) {
        ExpectedVersion.require(target, version);
        guard.lock();
        var actor = authorization.require(tokenDigest, "iam:account:state");
        Account current = currentAccount(target, version);
        if (current.roleIds().contains(SystemRole.ADMIN_ID) && !actor.systemAdministrator())
            throw IamException.forbidden("只有系统管理员可以修改系统管理员账号");
        return save(current, current.changeEnabled(enabled), actor, "account.state");
    }

    /**
     * 整体替换角色分配；只有系统管理员可以分配，角色须存在且启用，最多 20 个。
     */
    @Transactional
    public CommandResult roles(String tokenDigest, long target, long version, Set<Long> roleIds) {
        ExpectedVersion.require(target, version);
        if (roleIds == null || roleIds.size() > Account.MAX_ROLES)
            throw IamException.invalid("角色分配无效");
        guard.lock();
        var actor = authorization.requireSystem(tokenDigest, "iam:account:role");
        Account current = currentAccount(target, version);
        for (long roleId : new TreeSet<>(roleIds)) {
            Role role = roles.lock(roleId);
            if (role == null || !role.enabled()) throw IamException.unprocessable("ROLE_UNAVAILABLE", "角色不存在或已停用");
        }
        return save(current, current.changeRoles(roleIds), actor, "account.roles");
    }

    /**
     * 整体替换成员归属；存在归属时恰有一个主部门，组织须存在。
     */
    @Transactional
    public CommandResult memberships(String tokenDigest, long target, long version, List<MembershipInput> inputs) {
        ExpectedVersion.require(target, version);
        if (inputs == null) throw IamException.invalid("成员归属无效");
        Set<Membership> memberships = toMemberships(inputs);
        organizations.lockStructure();
        var actor = authorization.requireSystem(tokenDigest, "iam:member:manage");
        Account current = currentAccount(target, version);
        Account changed = current.changeMemberships(memberships);
        var units = changed.memberships().stream().map(Membership::unitId).toList();
        if (organizations.existing(units) != units.size())
            throw IamException.unprocessable("MEMBERSHIP_UNIT_UNKNOWN", "组织不存在");
        return save(current, changed, actor, "account.memberships");
    }

    private static Set<Membership> toMemberships(List<MembershipInput> inputs) {
        Set<Membership> values = new HashSet<>();
        for (MembershipInput input : inputs) {
            if (!values.add(new Membership(input.unitId(), input.primary())))
                throw IamException.invalid("成员归属重复");
        }
        return values;
    }

    /**
     * 不加行锁读取并核对客户端版本；保存时再按同一版本条件更新，并发修改只有一个成功。
     */
    private Account currentAccount(long target, long version) {
        Account account = accounts.find(target);
        return ExpectedVersion.current(account, Account::version, version, "ACCOUNT_NOT_FOUND");
    }

    /**
     * 保存变更并在同一事务写审计；变更会使最后一名系统管理员失去资格时拒绝。
     */
    private CommandResult save(Account current, Account changed, CurrentIdentity actor, String action) {
        if (current.isSystemAdministrator()
                && !changed.isSystemAdministrator()
                && accounts.systemAdministrators() <= 1) {
            throw IamException.conflict("LAST_ADMINISTRATOR", "必须保留至少一名启用的系统管理员");
        }
        if (changed != current) {
            ExpectedVersion.saved(accounts.update(current, changed), "账号版本已变化");
            audit.record(new AuditEvent(Long.toString(actor.accountId()), changed.id(), action));
        }
        return new CommandResult(changed.id(), changed.version());
    }
}
