package org.domainforge.iam.interfaces.http.menu;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.iam.application.command.menu.ManageMenu;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.iam.application.query.menu.MenuQueries;
import org.domainforge.iam.interfaces.http.organization.MoveRequest;
import org.domainforge.iam.interfaces.http.protocol.ChangeResponse;
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
 * 菜单：本人可见菜单只需登录；目录查询需要 iam:menu:read，变更仅系统管理员。
 *
 * <p>可见菜单只服务展示，不授予 API 权限，后端仍按权限执行。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class MenuController {
    private final MenuQueries queries;
    private final ManageMenu management;
    private final CurrentAccess access;
    private final CurrentIdentityAccess identity;

    /**
     * 装配查询合同与应用命令。
     */
    public MenuController(
            MenuQueries queries, ManageMenu management, CurrentAccess access, CurrentIdentityAccess identity) {
        this.queries = queries;
        this.management = management;
        this.access = access;
        this.identity = identity;
    }

    /**
     * 本人当前权限可见的菜单项。
     */
    @SaCheckLogin
    @GetMapping("/api/me/menus")
    @Operation(summary = "读取本人可见菜单")
    public ApiResponse<List<MenuResponse>> mine() {
        return ApiResponse.success(
                queries.visible(access.current().permissions()).stream().map(MenuResponse::of).toList());
    }

    /**
     * 全部菜单项。
     */
    @SaCheckPermission("iam:menu:read")
    @GetMapping("/api/menus")
    @Operation(summary = "查询菜单目录")
    public ApiResponse<List<MenuResponse>> all() {
        return ApiResponse.success(queries.all().stream().map(MenuResponse::of).toList());
    }

    /**
     * 创建菜单项。
     */
    @SaCheckPermission("iam:menu:manage")
    @PostMapping("/api/menus")
    @Operation(summary = "创建菜单项")
    public ApiResponse<ChangeResponse> create(@RequestBody CreateMenuRequest input) {
        return ApiResponse.success(ChangeResponse.of(management.create(digest(), input.toInput())));
    }

    /**
     * 修改名称、路径、排序与关联权限。
     */
    @SaCheckPermission("iam:menu:manage")
    @PutMapping("/api/menus/{id}")
    @Operation(summary = "修改菜单项")
    public ApiResponse<ChangeResponse> update(@PathVariable long id, @RequestBody UpdateMenuRequest input) {
        return ApiResponse.success(ChangeResponse.of(management.update(
                digest(), id, input.expectedVersion(), input.name(), input.path(), input.sortOrder(),
                input.permission())));
    }

    /**
     * 移到新父节点，parentId 为 null 表示顶层。
     */
    @SaCheckPermission("iam:menu:manage")
    @PutMapping("/api/menus/{id}/parent")
    @Operation(summary = "移动菜单项")
    public ApiResponse<ChangeResponse> move(@PathVariable long id, @RequestBody MoveRequest input) {
        return ApiResponse.success(ChangeResponse.of(
                management.move(digest(), id, input.expectedVersion(), input.parentId())));
    }

    /**
     * 启用或停用。
     */
    @SaCheckPermission("iam:menu:manage")
    @PutMapping("/api/menus/{id}/state")
    @Operation(summary = "启停菜单项")
    public ApiResponse<ChangeResponse> state(@PathVariable long id, @RequestBody StateRequest input) {
        return ApiResponse.success(ChangeResponse.of(
                management.state(digest(), id, input.expectedVersion(), input.enabled())));
    }

    /**
     * 删除没有下级的菜单项。
     */
    @SaCheckPermission("iam:menu:manage")
    @DeleteMapping("/api/menus/{id}")
    @Operation(summary = "删除菜单项")
    public ApiResponse<Void> delete(@PathVariable long id, @RequestParam long expectedVersion) {
        management.delete(digest(), id, expectedVersion);
        return ApiResponse.success(null);
    }

    private String digest() {
        return identity.current().tokenDigest();
    }
}
