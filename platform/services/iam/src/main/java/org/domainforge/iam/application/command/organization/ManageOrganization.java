package org.domainforge.iam.application.command.organization;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.command.DomainRules;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.domainforge.iam.domain.model.organization.OrganizationUnit;
import org.domainforge.iam.domain.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织树管理：创建、改名、移动、启停与删除，只有系统管理员可以执行。
 *
 * <p>所有结构写入先取组织协调锁，再锁目标节点；移动用新父节点的祖先链检查成环。
 */
@Service
public class ManageOrganization {
    private static final String PERMISSION = "iam:org:manage";
    private final CurrentAuthorization authorization;
    private final OrganizationRepository units;
    private final Identifiers identifiers;
    private final AuditTrail audit;

    /**
     * 只依赖组织仓储与审计端口。
     */
    public ManageOrganization(
            CurrentAuthorization authorization,
            OrganizationRepository units,
            Identifiers identifiers,
            AuditTrail audit) {
        this.authorization = authorization;
        this.units = units;
        this.identifiers = identifiers;
        this.audit = audit;
    }

    /**
     * 在已有父节点下创建启用的组织；同级重名由数据库约束拒绝。
     */
    @Transactional
    public CommandResult create(String tokenDigest, long parentId, String name) {
        units.lockStructure();
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        if (units.lock(parentId) == null) throw new IamException(IamFailure.CONFLICT, "父组织不存在");
        OrganizationUnit unit = DomainRules.apply(() -> OrganizationUnit.create(identifiers.next(), parentId, name));
        requireUniqueName(unit);
        units.insert(unit);
        record(actor, unit.id(), "org.create");
        return new CommandResult(unit.id(), unit.version());
    }

    /**
     * 修改名称。
     */
    @Transactional
    public CommandResult rename(String tokenDigest, long id, long version, String name) {
        DomainRules.requireVersion(id, version);
        units.lockStructure();
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        OrganizationUnit current = lock(id, version);
        OrganizationUnit changed = DomainRules.apply(() -> current.rename(name));
        requireUniqueName(changed);
        return save(actor, current, changed, "org.update");
    }

    /**
     * 移到新父节点下；不能移到自身或下级之下，根不能移动。
     */
    @Transactional
    public CommandResult move(String tokenDigest, long id, long version, long parentId) {
        DomainRules.requireVersion(id, version);
        units.lockStructure();
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        OrganizationUnit current = lock(id, version);
        var ancestors = units.ancestors(parentId);
        if (ancestors.isEmpty()) throw new IamException(IamFailure.CONFLICT, "父组织不存在");
        OrganizationUnit changed = DomainRules.apply(() -> current.moveTo(parentId, ancestors));
        requireUniqueName(changed);
        return save(actor, current, changed, "org.move");
    }

    /**
     * 启用或停用；停用使该节点及下级不再是有效部门，根不能停用。
     */
    @Transactional
    public CommandResult state(String tokenDigest, long id, long version, boolean enabled) {
        DomainRules.requireVersion(id, version);
        units.lockStructure();
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        OrganizationUnit current = lock(id, version);
        return save(actor, current, DomainRules.apply(() -> current.changeEnabled(enabled)), "org.state");
    }

    /**
     * 删除没有子节点、成员和自定义范围引用的组织；有引用时先迁移或解除。
     */
    @Transactional
    public void delete(String tokenDigest, long id, long version) {
        DomainRules.requireVersion(id, version);
        units.lockStructure();
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        OrganizationUnit current = lock(id, version);
        DomainRules.check(current::requireDeletable);
        if (units.referenced(id)) throw new IamException(IamFailure.CONFLICT, "组织仍有下级、成员或范围引用");
        units.delete(id);
        record(actor, id, "org.delete");
    }

    private void requireUniqueName(OrganizationUnit unit) {
        if (unit.parentId() != null && units.nameTaken(unit.parentId(), unit.name(), unit.id()))
            throw new IamException(IamFailure.CONFLICT, "同级已有同名组织");
    }

    private OrganizationUnit lock(long id, long version) {
        OrganizationUnit unit = units.lock(id);
        return DomainRules.requireCurrent(unit, unit == null ? 0 : unit.version(), version);
    }

    private CommandResult save(
            CurrentIdentity actor, OrganizationUnit current, OrganizationUnit changed, String action) {
        if (changed != current) {
            units.update(changed, current.version());
            record(actor, changed.id(), action);
        }
        return new CommandResult(changed.id(), changed.version());
    }

    private void record(CurrentIdentity actor, long target, String action) {
        audit.record(new AuditEvent(Long.toString(actor.accountId()), target, action));
    }
}
