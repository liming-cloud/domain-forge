package org.domainforge.iam.interfaces.http.account;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.iam.application.command.account.ManageAccount;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.iam.application.query.account.AccountQueries;
import org.domainforge.iam.interfaces.http.protocol.ChangeResponse;
import org.domainforge.iam.interfaces.http.protocol.StateRequest;
import org.domainforge.shared.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;

/**
 * 账号纯查询走应用查询出口，管理命令必须同时经过原生策略与事务内当前资格检查。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AccountController {
    private final CurrentAccess access;
    private final AccountQueries queries;
    private final ManageAccount management;
    private final CurrentIdentityAccess identity;

    /**
     * 只依赖技术无关查询合同和应用命令，不暴露领域账号凭据。
     */
    public AccountController(
            CurrentAccess access,
            AccountQueries queries,
            ManageAccount management,
            CurrentIdentityAccess identity) {
        this.access = access;
        this.queries = queries;
        this.management = management;
        this.identity = identity;
    }

    /**
     * 本人身份来自服务端当前会话，接口不接收 accountId 参数。
     */
    @SaCheckLogin
    @GetMapping("/api/me")
    @Operation(summary = "读取本人最小资料")
    public ApiResponse<AccountResponse> me() {
        return ApiResponse.success(AccountResponse.of(queries.self(access.current().accountId())));
    }

    /**
     * 单次业务投影 SQL，按 ID 升序、最多 100 项，不执行总数查询；after 为上一页的 nextCursor。
     */
    @SaCheckPermission("iam:account:read")
    @GetMapping("/api/accounts")
    @Operation(summary = "分页查询账号")
    public ApiResponse<AccountPageResponse> accounts(
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(AccountPageResponse.of(queries.list(after, limit)));
    }

    /**
     * 账号详情：状态、角色分配与成员归属。
     */
    @SaCheckPermission("iam:account:read")
    @GetMapping("/api/accounts/{id}")
    @Operation(summary = "读取账号详情")
    public ApiResponse<AccountDetailResponse> account(@PathVariable long id) {
        var detail = queries.detail(id);
        if (detail == null) throw new IamException(IamFailure.CONFLICT, "账号不存在");
        return ApiResponse.success(AccountDetailResponse.of(detail));
    }

    /**
     * 启用与禁用均要求明确版本，不会恢复旧会话。
     */
    @SaCheckPermission("iam:account:state")
    @PutMapping("/api/accounts/{id}/state")
    @Operation(summary = "变更账号启用状态")
    public ApiResponse<ChangeResponse> state(@PathVariable long id, @RequestBody StateRequest input) {
        return ApiResponse.success(ChangeResponse.of(management.state(
                identity.current().tokenDigest(), id, input.expectedVersion(), input.enabled())));
    }

    /**
     * 整体替换角色分配；仅系统管理员，至少保留一名启用的系统管理员。
     */
    @SaCheckPermission("iam:account:role")
    @PutMapping("/api/accounts/{id}/roles")
    @Operation(summary = "分配账号角色")
    public ApiResponse<ChangeResponse> roles(@PathVariable long id, @RequestBody RolesRequest input) {
        return ApiResponse.success(ChangeResponse.of(management.roles(
                identity.current().tokenDigest(), id, input.expectedVersion(), new HashSet<>(input.roleIds()))));
    }

    /**
     * 整体替换成员归属；仅系统管理员。
     */
    @SaCheckPermission("iam:member:manage")
    @PutMapping("/api/accounts/{id}/memberships")
    @Operation(summary = "变更成员归属")
    public ApiResponse<ChangeResponse> memberships(@PathVariable long id, @RequestBody MembershipsRequest input) {
        return ApiResponse.success(ChangeResponse.of(management.memberships(
                identity.current().tokenDigest(), id, input.expectedVersion(), input.toDomain())));
    }
}
