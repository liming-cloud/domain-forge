package org.domainforge.iam.application.command.role;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.command.DomainRules;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.domainforge.iam.domain.model.role.PermissionGrant;
import org.domainforge.iam.domain.model.role.Role;
import org.domainforge.iam.domain.repository.OrganizationRepository;
import org.domainforge.iam.domain.repository.PermissionCatalog;
import org.domainforge.iam.domain.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 角色管理：创建、改名、启停、授予与删除，只有系统管理员可以执行。
 *
 * <p>授予变更的锁顺序为：组织协调 → 目录共享锁 → 角色，保证引用的组织与权限在提交前不被删除。
 */
@Service
public class ManageRole {
    private static final String PERMISSION = "iam:role:manage";
    private final CurrentAuthorization authorization;
    private final RoleRepository roles;
    private final PermissionCatalog catalog;
    private final OrganizationRepository organizations;
    private final Identifiers identifiers;
    private final AuditTrail audit;

    /**
     * 只依赖仓储合同与审计端口。
     */
    public ManageRole(
            CurrentAuthorization authorization,
            RoleRepository roles,
            PermissionCatalog catalog,
            OrganizationRepository organizations,
            Identifiers identifiers,
            AuditTrail audit) {
        this.authorization = authorization;
        this.roles = roles;
        this.catalog = catalog;
        this.organizations = organizations;
        this.identifiers = identifiers;
        this.audit = audit;
    }

    /**
     * 创建启用、无授予的普通角色；代码重复由数据库约束拒绝。
     */
    @Transactional
    public CommandResult create(String tokenDigest, String code, String name) {
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        Role role = DomainRules.apply(() -> Role.create(identifiers.next(), code, name));
        if (roles.codeTaken(role.code())) throw new IamException(IamFailure.CONFLICT, "角色代码已存在");
        roles.insert(role);
        record(actor, role.id(), "role.create");
        return new CommandResult(role.id(), role.version());
    }

    /**
     * 修改名称；代码不可修改。
     */
    @Transactional
    public CommandResult rename(String tokenDigest, long id, long version, String name) {
        DomainRules.requireVersion(id, version);
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        Role current = lock(id, version);
        return save(actor, current, DomainRules.apply(() -> current.rename(name)), "role.update");
    }

    /**
     * 启用或停用；停用后其授予在下一次授权检查即不再生效。
     */
    @Transactional
    public CommandResult state(String tokenDigest, long id, long version, boolean enabled) {
        DomainRules.requireVersion(id, version);
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        Role current = lock(id, version);
        return save(actor, current, DomainRules.apply(() -> current.changeEnabled(enabled)), "role.state");
    }

    /**
     * 整体替换授予：每个权限须在目录中，范围与目录声明一致，自定义范围的组织须存在。
     */
    @Transactional
    public CommandResult grants(String tokenDigest, long id, long version, Set<PermissionGrant> grants) {
        DomainRules.requireVersion(id, version);
        if (grants == null || grants.size() > PermissionCatalog.MAX_TOTAL)
            throw new IamException(IamFailure.INVALID_REQUEST, "授予无效");
        organizations.lockStructure();
        catalog.lock(false);
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        Role current = lock(id, version);
        var definitions = catalog.find(grants.stream().map(PermissionGrant::permission).toList());
        for (PermissionGrant grant : grants) {
            DomainRules.check(() -> grant.requireCompatible(definitions.get(grant.permission()), current.system()));
            if (organizations.existing(grant.unitIds()) != grant.unitIds().size())
                throw new IamException(IamFailure.CONFLICT, "范围中的组织不存在");
        }
        return save(actor, current, DomainRules.apply(() -> current.replaceGrants(grants)), "role.grants");
    }

    /**
     * 删除已停用且未分配的普通角色。
     */
    @Transactional
    public void delete(String tokenDigest, long id, long version) {
        DomainRules.requireVersion(id, version);
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        Role current = lock(id, version);
        DomainRules.check(current::requireDeletable);
        if (roles.assignments(id) > 0) throw new IamException(IamFailure.CONFLICT, "角色仍分配给账号，先撤销分配");
        roles.delete(id);
        record(actor, id, "role.delete");
    }

    private Role lock(long id, long version) {
        Role role = roles.lock(id);
        return DomainRules.requireCurrent(role, role == null ? 0 : role.version(), version);
    }

    private CommandResult save(CurrentIdentity actor, Role current, Role changed, String action) {
        if (changed != current) {
            roles.update(changed, current.version());
            record(actor, changed.id(), action);
        }
        return new CommandResult(changed.id(), changed.version());
    }

    private void record(CurrentIdentity actor, long target, String action) {
        audit.record(new AuditEvent(Long.toString(actor.accountId()), target, action));
    }
}
