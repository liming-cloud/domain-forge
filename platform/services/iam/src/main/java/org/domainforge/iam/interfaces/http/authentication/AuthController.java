package org.domainforge.iam.interfaces.http.authentication;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaIgnore;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.domainforge.iam.application.command.authentication.ChangePassword;
import org.domainforge.iam.application.command.authentication.Login;
import org.domainforge.iam.application.command.authentication.LoginResult;
import org.domainforge.iam.application.command.authentication.Logout;
import org.domainforge.iam.application.command.registration.AcceptInvitation;
import org.domainforge.iam.application.port.authentication.CurrentIdentityAccess;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.interfaces.http.registration.RegistrationRequest;
import org.domainforge.shared.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 认证HTTP适配不接受客户端主体，只把原生当前身份和直接来源地址交给应用。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AuthController {
    private final Login login;
    private final AcceptInvitation registration;
    private final ChangePassword passwords;
    private final Logout logout;
    private final CurrentIdentityAccess identity;

    /**
     * 装配实际应用用例；不在控制器访问Mapper、Redis或密码编码器。
     */
    public AuthController(
            Login login,
            AcceptInvitation registration,
            ChangePassword passwords,
            Logout logout,
            CurrentIdentityAccess identity) {
        this.login = login;
        this.registration = registration;
        this.passwords = passwords;
        this.logout = logout;
        this.identity = identity;
    }

    /**
     * 仅直接TCP来源用于防护，不接受Forwarded头声明的来源地址。
     */
    @SaIgnore
    @PostMapping("/api/auth/login")
    @Operation(summary = "登录", description = "默认保护启用；返回的原生Bearer令牌只在SQL提交确认后交付")
    public ApiResponse<LoginResult> login(
            @RequestBody LoginRequest input, HttpServletRequest request) {
        request.setAttribute("iam.operationId", UUID.randomUUID());
        return ApiResponse.success(
                login.execute(input.loginName(), input.password(), request.getRemoteAddr()));
    }

    /**
     * 注册须有效邀请；同操作ID与相同输入重放不会再次建号。
     */
    @SaIgnore
    @PostMapping("/api/auth/invitations/accept")
    @Operation(summary = "接受注册邀请", description = "邀请秘密放请求体；operationId用于24小时内同输入重试")
    public ApiResponse<CommandResult> register(
            @RequestBody RegistrationRequest input, HttpServletRequest request) {
        request.setAttribute("iam.operationId", input.operationId());
        return ApiResponse.success(
                registration.execute(
                        input.operationId(), input.invitationSecret(), input.loginName(), input.password()));
    }

    /**
     * 改密后全部旧会话失效；客户端须用新密码重新登录。
     */
    @SaCheckLogin
    @PostMapping("/api/auth/password")
    @Operation(summary = "修改本人密码", description = "当前会话及旧密码必需；SQL提交后所有旧会话立即拒绝")
    public ApiResponse<CommandResult> password(
            @RequestBody PasswordChangeRequest input, HttpServletRequest request) {
        request.setAttribute("iam.operationId", input.operationId());
        return ApiResponse.success(
                passwords.execute(
                        identity.current(), input.operationId(), input.oldPassword(), input.newPassword()));
    }

    /**
     * 退出只使用原生解析的当前令牌，不能通过请求体指定其他账号或会话。
     */
    @SaCheckLogin
    @PostMapping("/api/auth/logout")
    @Operation(summary = "退出当前会话")
    public ApiResponse<CommandResult> logout(
            @RequestBody OperationRequest input, HttpServletRequest request) {
        request.setAttribute("iam.operationId", input.operationId());
        return ApiResponse.success(
                logout.execute(identity.current(), identity.token(), input.operationId()));
    }
}
