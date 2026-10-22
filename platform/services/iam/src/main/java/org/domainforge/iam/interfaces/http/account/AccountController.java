package org.domainforge.iam.interfaces.http.account;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.iam.application.command.account.ManageAccount;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.query.account.AccountPage;
import org.domainforge.iam.application.query.account.AccountQueries;
import org.domainforge.iam.application.query.account.AccountView;
import org.domainforge.shared.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账号纯查询走应用查询出口，管理命令必须同时经过原生策略与事务内当前资格检查。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AccountController {
  private final CurrentAccess access;
  private final AccountQueries queries;
  private final ManageAccount management;
  private final CurrentIdentityAccess identity;

  /** 只依赖技术无关查询合同和应用命令，不暴露领域账号凭据。 */
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

  /** 本人身份来自服务端当前会话，接口不接收accountId参数。 */
  @SaCheckLogin
  @GetMapping("/api/me")
  @Operation(summary = "读取本人最小资料")
  public ApiResponse<AccountView> me() {
    return ApiResponse.success(queries.self(access.current().accountId()));
  }

  /** 单次业务投影SQL，固定UUID升序和最多100项，不执行总数查询。 */
  @SaCheckPermission("iam:account:read")
  @GetMapping("/api/accounts")
  @Operation(summary = "分页查询账号")
  public ApiResponse<AccountPage> accounts(
      @RequestParam(required = false) UUID after, @RequestParam(defaultValue = "20") int limit) {
    return ApiResponse.success(queries.list(after, limit));
  }

  /** 整体替换明确权限集合；并发版本和最后完整管理者由应用协调。 */
  @SaCheckPermission("iam:grant:manage")
  @PutMapping("/api/accounts/{id}/permissions")
  @Operation(summary = "变更直接权限")
  public ApiResponse<CommandResult> permissions(
      @PathVariable UUID id, @RequestBody PermissionRequest input, HttpServletRequest request) {
    request.setAttribute("iam.operationId", input.operationId());
    return ApiResponse.success(
        management.permissions(
            identity.current().tokenDigest(),
            input.operationId(),
            id,
            input.expectedVersion(),
            input.permissions()));
  }

  /** 启用与禁用均要求明确版本，不会恢复旧会话。 */
  @SaCheckPermission("iam:account:state")
  @PutMapping("/api/accounts/{id}/state")
  @Operation(summary = "变更账号启用状态")
  public ApiResponse<CommandResult> state(
      @PathVariable UUID id, @RequestBody AccountStateRequest input, HttpServletRequest request) {
    request.setAttribute("iam.operationId", input.operationId());
    return ApiResponse.success(
        management.state(
            identity.current().tokenDigest(),
            input.operationId(),
            id,
            input.expectedVersion(),
            input.enabled()));
  }
}
