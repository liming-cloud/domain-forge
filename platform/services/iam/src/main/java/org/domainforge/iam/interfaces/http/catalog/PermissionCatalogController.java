package org.domainforge.iam.interfaces.http.catalog;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import org.domainforge.iam.application.command.catalog.InstallPermissions;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.iam.application.query.catalog.PermissionQueries;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限目录：服务发布命名空间内的精确操作码，由系统管理员受控安装。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PermissionCatalogController {
    private final PermissionQueries queries;
    private final InstallPermissions installer;
    private final CurrentIdentityAccess identity;

    /**
     * 装配查询合同与应用命令。
     */
    public PermissionCatalogController(
            PermissionQueries queries, InstallPermissions installer, CurrentIdentityAccess identity) {
        this.queries = queries;
        this.installer = installer;
        this.identity = identity;
    }

    /**
     * 全部权限，按权限码排序。
     */
    @SaCheckPermission("iam:catalog:read")
    @GetMapping("/api/permissions")
    @Operation(summary = "查询权限目录")
    public List<PermissionBody> permissions() {
        return queries.list().stream().map(PermissionBody::of).toList();
    }

    /**
     * 整体安装或升级一个命名空间；被引用的权限不能移除或改变范围声明。
     */
    @SaCheckPermission("iam:catalog:manage")
    @PutMapping("/api/permission-namespaces/{namespace}")
    @Operation(summary = "安装权限命名空间")
    public Integer install(@PathVariable String namespace, @RequestBody InstallRequest input) {
        return installer.install(
                identity.current().tokenDigest(),
                namespace,
                input.permissions().stream().map(PermissionBody::toInput).toList());
    }
}
