package org.domainforge.iam.interfaces.http.role;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import org.domainforge.iam.application.command.role.ManageRole;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.iam.application.query.role.RoleQueries;
import org.domainforge.iam.interfaces.http.protocol.ChangeResponse;
import org.domainforge.iam.interfaces.http.protocol.RenameRequest;
import org.domainforge.iam.interfaces.http.protocol.StateRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与授权范围：查询需要 iam:role:read，变更仅系统管理员。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class RoleController {
    private final RoleQueries queries;
    private final ManageRole management;
    private final CurrentIdentityAccess identity;

    /**
     * 装配查询合同与应用命令。
     */
    public RoleController(RoleQueries queries, ManageRole management, CurrentIdentityAccess identity) {
        this.queries = queries;
        this.management = management;
        this.identity = identity;
    }

    /**
     * 全部角色。
     */
    @SaCheckPermission("iam:role:read")
    @GetMapping("/api/roles")
    @Operation(summary = "查询角色")
    public List<RoleResponse> roles() {
        return queries.list().stream().map(RoleResponse::of).toList();
    }

    /**
     * 角色详情与授予。
     */
    @SaCheckPermission("iam:role:read")
    @GetMapping("/api/roles/{id}")
    @Operation(summary = "读取角色详情")
    public RoleDetailResponse role(@PathVariable long id) {
        var detail = queries.detail(id);
        if (detail == null) throw IamException.notFound("ROLE_NOT_FOUND", "角色不存在");
        return RoleDetailResponse.of(detail);
    }

    /**
     * 创建无授予的普通角色。
     */
    @SaCheckPermission("iam:role:manage")
    @PostMapping("/api/roles")
    @Operation(summary = "创建角色")
    public ChangeResponse create(@RequestBody CreateRoleRequest input) {
        return ChangeResponse.of(management.create(digest(), input.code(), input.name()));
    }

    /**
     * 修改名称。
     */
    @SaCheckPermission("iam:role:manage")
    @PutMapping("/api/roles/{id}/name")
    @Operation(summary = "修改角色名称")
    public ChangeResponse rename(@PathVariable long id, @RequestBody RenameRequest input) {
        return ChangeResponse.of(
                management.rename(digest(), id, input.expectedVersion(), input.name()));
    }

    /**
     * 启用或停用；系统角色不能停用。
     */
    @SaCheckPermission("iam:role:manage")
    @PutMapping("/api/roles/{id}/state")
    @Operation(summary = "启停角色")
    public ChangeResponse state(@PathVariable long id, @RequestBody StateRequest input) {
        return ChangeResponse.of(
                management.state(digest(), id, input.expectedVersion(), input.enabled()));
    }

    /**
     * 整体替换授予与数据范围。
     */
    @SaCheckPermission("iam:role:manage")
    @PutMapping("/api/roles/{id}/grants")
    @Operation(summary = "替换角色授予")
    public ChangeResponse grants(@PathVariable long id, @RequestBody GrantsRequest input) {
        return ChangeResponse.of(
                management.grants(digest(), id, input.expectedVersion(), input.toInput()));
    }

    /**
     * 删除已停用且未分配的角色。
     */
    @SaCheckPermission("iam:role:manage")
    @DeleteMapping("/api/roles/{id}")
    @Operation(summary = "删除角色")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestParam long expectedVersion) {
        management.delete(digest(), id, expectedVersion);
    }

    private String digest() {
        return identity.current().tokenDigest();
    }
}
