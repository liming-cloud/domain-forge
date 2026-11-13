package org.domainforge.iam.application.command.menu;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.command.ExpectedVersion;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.domainforge.iam.domain.model.menu.MenuItem;
import org.domainforge.iam.domain.model.menu.MenuType;
import org.domainforge.iam.domain.repository.MenuRepository;
import org.domainforge.iam.domain.repository.PermissionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 菜单树管理，只有系统管理员可以执行；可见菜单只服务展示，不授予 API 权限。
 *
 * <p>锁顺序为：菜单协调 → 目录共享锁 → 菜单项。
 */
@Service
public class ManageMenu {
    private static final String PERMISSION = "iam:menu:manage";
    private final CurrentAuthorization authorization;
    private final MenuRepository menus;
    private final PermissionCatalog catalog;
    private final Identifiers identifiers;
    private final AuditTrail audit;

    /**
     * 只依赖仓储合同与审计端口。
     */
    public ManageMenu(
            CurrentAuthorization authorization,
            MenuRepository menus,
            PermissionCatalog catalog,
            Identifiers identifiers,
            AuditTrail audit) {
        this.authorization = authorization;
        this.menus = menus;
        this.catalog = catalog;
        this.identifiers = identifiers;
        this.audit = audit;
    }

    /**
     * 创建菜单项；关联权限须在目录中，父节点须存在且不是按钮。
     */
    @Transactional
    public CommandResult create(String tokenDigest, MenuInput input) {
        menus.lockStructure();
        catalog.lock(false);
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        MenuItem item = MenuItem.create(
                identifiers.next(), input.parentId(), MenuType.valueOf(input.type()), input.code(), input.name(),
                input.path(),
                input.sortOrder(), input.permission());
        requirePermission(item.permission());
        if (menus.codeTaken(item.code())) throw IamException.conflict("MENU_CODE_TAKEN", "菜单代码已存在");
        MenuItem parent = input.parentId() == null ? null : menus.lock(input.parentId());
        if (input.parentId() != null && parent == null)
            throw IamException.unprocessable("MENU_PARENT_UNKNOWN", "父菜单不存在");
        item.requireParent(parent);
        menus.insert(item);
        record(actor, item.id(), "menu.create");
        return new CommandResult(item.id(), item.version());
    }

    /**
     * 修改名称、路径、排序与关联权限。
     */
    @Transactional
    public CommandResult update(
            String tokenDigest, long id, long version, String name, String path, int sortOrder, String permission) {
        ExpectedVersion.require(id, version);
        menus.lockStructure();
        catalog.lock(false);
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        MenuItem current = lock(id, version);
        MenuItem changed = current.update(name, path, sortOrder, permission);
        requirePermission(changed.permission());
        return save(actor, current, changed, "menu.update");
    }

    /**
     * 移到新父节点（null 为顶层）；不能成环，按钮只能在页面下。
     */
    @Transactional
    public CommandResult move(String tokenDigest, long id, long version, Long parentId) {
        ExpectedVersion.require(id, version);
        menus.lockStructure();
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        MenuItem current = lock(id, version);
        MenuItem parent = parentId == null ? null : menus.lock(parentId);
        if (parentId != null && parent == null) throw IamException.unprocessable("MENU_PARENT_UNKNOWN", "父菜单不存在");
        current.requireParent(parent);
        List<Long> ancestors = parentId == null ? List.of() : menus.ancestors(parentId);
        return save(actor, current, current.moveTo(parentId, ancestors), "menu.move");
    }

    /**
     * 启用或停用；停用项及其下级不再可见。
     */
    @Transactional
    public CommandResult state(String tokenDigest, long id, long version, boolean enabled) {
        ExpectedVersion.require(id, version);
        menus.lockStructure();
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        MenuItem current = lock(id, version);
        return save(actor, current, current.changeEnabled(enabled), "menu.state");
    }

    /**
     * 删除没有下级的菜单项。
     */
    @Transactional
    public void delete(String tokenDigest, long id, long version) {
        ExpectedVersion.require(id, version);
        menus.lockStructure();
        var actor = authorization.requireSystem(tokenDigest, PERMISSION);
        lock(id, version);
        if (menus.hasChildren(id)) throw IamException.conflict("MENU_IN_USE", "菜单仍有下级");
        menus.delete(id);
        record(actor, id, "menu.delete");
    }

    private void requirePermission(String permission) {
        if (permission != null && catalog.find(List.of(permission)).isEmpty())
            throw IamException.unprocessable("MENU_PERMISSION_UNKNOWN", "关联的权限不在目录中");
    }

    private MenuItem lock(long id, long version) {
        MenuItem item = menus.lock(id);
        return ExpectedVersion.current(item, MenuItem::version, version, "MENU_NOT_FOUND");
    }

    private CommandResult save(CurrentIdentity actor, MenuItem current, MenuItem changed, String action) {
        if (changed != current) {
            ExpectedVersion.saved(menus.update(changed, current.version()), "菜单版本已变化");
            record(actor, changed.id(), action);
        }
        return new CommandResult(changed.id(), changed.version());
    }

    private void record(CurrentIdentity actor, long target, String action) {
        audit.record(new AuditEvent(Long.toString(actor.accountId()), target, action));
    }
}
