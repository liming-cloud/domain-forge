package org.domainforge.iam.interfaces.http.registration;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.domainforge.iam.application.command.registration.InvitationIssued;
import org.domainforge.iam.application.command.registration.IssueInvitation;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.shared.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 管理邀请入口不发送邮件或创建预设密码；秘密仅首次交付给有权管理者。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class InvitationController {
  private final IssueInvitation invitations;
  private final CurrentIdentityAccess identity;

  /** 邀请操作复用同一请求的可信身份，命令仍在事务内重查资格。 */
  public InvitationController(IssueInvitation invitations, CurrentIdentityAccess identity) {
    this.invitations = invitations;
    this.identity = identity;
  }

  /** 响应丢失后重放secret为空，须显式替换旧邀请；已消费邀请不能重发。 */
  @SaCheckPermission("iam:invitation:create")
  @PostMapping("/api/invitations")
  @Operation(summary = "创建或重发注册邀请", description = "秘密只首次返回；重放返回已创建事实，replacesInvitationId显式使旧邀请失效")
  public ApiResponse<InvitationIssued> issue(
      @RequestBody InvitationRequest input, HttpServletRequest request) {
    request.setAttribute("iam.operationId", input.operationId());
    return ApiResponse.success(
        invitations.execute(
            identity.current().tokenDigest(),
            input.operationId(),
            input.loginName(),
            input.replacesInvitationId()));
  }
}
