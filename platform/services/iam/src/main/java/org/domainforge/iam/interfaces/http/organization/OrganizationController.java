package org.domainforge.iam.interfaces.http.organization;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import org.domainforge.iam.application.command.organization.ManageOrganization;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.iam.application.query.organization.OrganizationQueries;
import org.domainforge.iam.interfaces.http.protocol.ChangeResponse;
import org.domainforge.iam.interfaces.http.protocol.RenameRequest;
import org.domainforge.iam.interfaces.http.protocol.StateRequest;
import org.domainforge.shared.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组织树：查询需要 iam:org:read，结构变更仅系统管理员。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class OrganizationController {
    private final OrganizationQueries queries;
    private final ManageOrganization management;
    private final CurrentIdentityAccess identity;

    /**
     * 装配查询合同与应用命令。
     */
    public OrganizationController(
            OrganizationQueries queries, ManageOrganization management, CurrentIdentityAccess identity) {
        this.queries = queries;
        this.management = management;
        this.identity = identity;
    }

    /**
     * 整棵组织树，父节点在前。
     */
    @SaCheckPermission("iam:org:read")
    @GetMapping("/api/organization-units")
    @Operation(summary = "查询组织树")
    public ApiResponse<List<OrganizationUnitResponse>> tree() {
        return ApiResponse.success(queries.tree().stream().map(OrganizationUnitResponse::of).toList());
    }

    /**
     * 在父组织下创建组织。
     */
    @SaCheckPermission("iam:org:manage")
    @PostMapping("/api/organization-units")
    @Operation(summary = "创建组织")
    public ApiResponse<ChangeResponse> create(@RequestBody CreateUnitRequest input) {
        return ApiResponse.success(ChangeResponse.of(
                management.create(digest(), input.parentId(), input.name())));
    }

    /**
     * 修改名称。
     */
    @SaCheckPermission("iam:org:manage")
    @PutMapping("/api/organization-units/{id}/name")
    @Operation(summary = "修改组织名称")
    public ApiResponse<ChangeResponse> rename(@PathVariable long id, @RequestBody RenameRequest input) {
        return ApiResponse.success(ChangeResponse.of(
                management.rename(digest(), id, input.expectedVersion(), input.name())));
    }

    /**
     * 移到新父组织；不能成环，根不能移动。
     */
    @SaCheckPermission("iam:org:manage")
    @PutMapping("/api/organization-units/{id}/parent")
    @Operation(summary = "移动组织")
    public ApiResponse<ChangeResponse> move(@PathVariable long id, @RequestBody MoveRequest input) {
        if (input.parentId() == null) throw new IllegalArgumentException("组织必须有父节点");
        return ApiResponse.success(ChangeResponse.of(
                management.move(digest(), id, input.expectedVersion(), input.parentId())));
    }

    /**
     * 启用或停用；根不能停用。
     */
    @SaCheckPermission("iam:org:manage")
    @PutMapping("/api/organization-units/{id}/state")
    @Operation(summary = "启停组织")
    public ApiResponse<ChangeResponse> state(@PathVariable long id, @RequestBody StateRequest input) {
        return ApiResponse.success(ChangeResponse.of(
                management.state(digest(), id, input.expectedVersion(), input.enabled())));
    }

    /**
     * 删除没有下级、成员和范围引用的组织。
     */
    @SaCheckPermission("iam:org:manage")
    @DeleteMapping("/api/organization-units/{id}")
    @Operation(summary = "删除组织")
    public ApiResponse<Void> delete(@PathVariable long id, @RequestParam long expectedVersion) {
        management.delete(digest(), id, expectedVersion);
        return ApiResponse.success(null);
    }

    private String digest() {
        return identity.current().tokenDigest();
    }
}
