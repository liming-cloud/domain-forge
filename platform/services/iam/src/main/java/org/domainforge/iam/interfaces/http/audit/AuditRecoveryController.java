package org.domainforge.iam.interfaces.http.audit;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.iam.application.command.audit.BackfillAudit;
import org.domainforge.iam.application.port.audit.ManualAudit;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.query.operation.ObserveOperation;
import org.domainforge.iam.application.query.operation.OperationObservation;
import org.domainforge.shared.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 结果核对与审计补录明确保留未知事实，不能通过重读接口提升权限。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AuditRecoveryController {
  private final BackfillAudit audit;
  private final ObserveOperation operations;
  private final CurrentAccess access;
  private final CurrentIdentityAccess identity;

  /** 只装配应用用例与可信当前身份。 */
  public AuditRecoveryController(
      BackfillAudit audit,
      ObserveOperation operations,
      CurrentAccess access,
      CurrentIdentityAccess identity) {
    this.audit = audit;
    this.operations = operations;
    this.access = access;
    this.identity = identity;
  }

  /** 补录者当前须有审计权限，原操作者和原结果保持未知，来源须可定位。 */
  @SaCheckPermission("iam:audit:read")
  @PostMapping("/api/audit/backfill")
  @Operation(summary = "显式补录缺失审计", description = "标记manual来源与未知原结果；不承诺恢复全部丢失事实")
  public ApiResponse<CommandResult> backfill(
      @RequestBody AuditBackfillRequest input, HttpServletRequest request) {
    request.setAttribute("iam.operationId", input.operationId());
    var entry =
        new ManualAudit(
            input.originalOperationId(),
            input.targetId(),
            input.action(),
            input.occurredAt(),
            input.provenance(),
            input.missingFields());
    return ApiResponse.success(
        audit.execute(identity.current().tokenDigest(), input.operationId(), entry));
  }

  /** actorId为空核对本人；查他人操作须审计权限。没有收据不代表未发生。 */
  @SaCheckLogin
  @GetMapping("/api/operations/{operationId}")
  @Operation(summary = "核对操作结果", description = "confirmed=false只表示缺少未过期收据，不能据此认定未提交")
  public ApiResponse<OperationObservation> operation(
      @PathVariable UUID operationId, @RequestParam(required = false) UUID actorId) {
    var current = access.current();
    return ApiResponse.success(
        operations.execute(current.accountId(), current.permissions(), operationId, actorId));
  }
}
