package org.domainforge.iam.application.command.catalog;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.domain.model.permission.PermissionDefinition;
import org.domainforge.iam.domain.model.permission.PermissionNamespace;
import org.domainforge.iam.domain.repository.PermissionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 服务权限命名空间的受控安装与升级，只有系统管理员可以执行。
 *
 * <p>被角色授予或菜单引用的权限不能移除，也不能改变是否需要数据范围；先解除引用再升级。
 */
@Service
public class InstallPermissions {
    private final CurrentAuthorization authorization;
    private final PermissionCatalog catalog;
    private final AuditTrail audit;

    /**
     * 只依赖目录仓储与审计端口。
     */
    public InstallPermissions(CurrentAuthorization authorization, PermissionCatalog catalog, AuditTrail audit) {
        this.authorization = authorization;
        this.catalog = catalog;
        this.audit = audit;
    }

    /**
     * 整体替换命名空间内容，返回安装后的权限数；内容不变时不写入也不审计。
     */
    @Transactional
    public int install(String tokenDigest, PermissionNamespace next) {
        catalog.lock(true);
        var actor = authorization.requireSystem(tokenDigest, "iam:catalog:manage");
        PermissionNamespace current = catalog.namespace(next.namespace());
        if (current.equals(next)) return next.permissions().size();
        if (catalog.countOutside(next.namespace()) + next.permissions().size() > PermissionCatalog.MAX_TOTAL)
            throw new IamException(IamFailure.CONFLICT, "权限目录总数超过1000");
        requireReferencesKept(current, next);
        catalog.replace(next);
        audit.record(new AuditEvent(Long.toString(actor.accountId()), actor.accountId(), "catalog.install"));
        return next.permissions().size();
    }

    /**
     * 仍被引用的权限必须保留，且保留时不能改变范围声明。
     */
    private void requireReferencesKept(PermissionNamespace current, PermissionNamespace next) {
        Map<String, PermissionDefinition> after = next.permissions().stream()
                .collect(Collectors.toMap(PermissionDefinition::code, definition -> definition));
        Set<String> changed = new HashSet<>();
        for (PermissionDefinition before : current.permissions()) {
            PermissionDefinition kept = after.get(before.code());
            if (kept == null || kept.scoped() != before.scoped()) changed.add(before.code());
        }
        if (!catalog.referenced(changed).isEmpty())
            throw new IamException(IamFailure.CONFLICT, "被角色或菜单引用的权限不能移除或改变范围声明");
    }
}
