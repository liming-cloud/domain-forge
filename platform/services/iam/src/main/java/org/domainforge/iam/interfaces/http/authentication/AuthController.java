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
import org.domainforge.iam.interfaces.http.protocol.ChangeResponse;
import org.domainforge.iam.interfaces.http.registration.RegistrationRequest;
import org.domainforge.shared.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 HTTP 适配不接受客户端主体，只把原生当前身份和直接来源地址交给应用。
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
     * 装配实际应用用例；不在控制器访问 Mapper、Redis 或密码编码器。
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
     * 仅直接 TCP 来源用于防护，不接受 Forwarded 头声明的来源地址。
     */
    @SaIgnore
    @PostMapping("/api/auth/login")
    @Operation(summary = "登录", description = "默认保护启用；返回的原生Bearer令牌只在SQL提交确认后交付")
    public ApiResponse<LoginResult> login(@RequestBody LoginRequest input, HttpServletRequest request) {
        return ApiResponse.success(login.execute(input.loginName(), input.password(), request.getRemoteAddr()));
    }

    /**
     * 注册须有效邀请；同一邀请只能成功一次，返回新账号 ID 与版本。
     */
    @SaIgnore
    @PostMapping("/api/auth/invitations/accept")
    @Operation(summary = "接受注册邀请", description = "邀请秘密放请求体；邀请已使用或过期时返回409")
    public ApiResponse<ChangeResponse> register(@RequestBody RegistrationRequest input) {
        return ApiResponse.success(ChangeResponse.of(
                registration.execute(input.invitationSecret(), input.loginName(), input.password())));
    }

    /**
     * 改密后全部旧会话失效；客户端须用新密码重新登录。
     */
    @SaCheckLogin
    @PostMapping("/api/auth/password")
    @Operation(summary = "修改本人密码", description = "当前会话及旧密码必需；SQL提交后所有旧会话立即拒绝")
    public ApiResponse<ChangeResponse> password(@RequestBody PasswordChangeRequest input) {
        return ApiResponse.success(ChangeResponse.of(
                passwords.execute(identity.current(), input.oldPassword(), input.newPassword())));
    }

    /**
     * 退出只使用原生解析的当前令牌，不能通过请求体指定其他账号或会话。
     */
    @SaCheckLogin
    @PostMapping("/api/auth/logout")
    @Operation(summary = "退出当前会话")
    public ApiResponse<Void> logout() {
        logout.execute(identity.current(), identity.token());
        return ApiResponse.success(null);
    }
}
