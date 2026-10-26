package org.domainforge.iam.interfaces.http.access;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.ApiResponse;
import org.domainforge.shared.CurrentSubject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供本系统业务服务读取当前用户的身份与权限；只读调用者自己的 Bearer 会话，不接收任意账号 ID。
 *
 * <p>后端使用 HTTP，TLS 在入口终止；调用方每个请求最多调用一次，跨请求不缓存允许结果。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class InternalAccessController {
    private final CurrentAccess identity;

    /**
     * 当前主体由公共访问组件按本请求令牌解析。
     */
    public InternalAccessController(CurrentAccess identity) {
        this.identity = identity;
    }

    /**
     * 返回账号 ID、角色与精确权限码；会话无效 401，依赖不可用 503。
     */
    @SaCheckLogin
    @GetMapping("/internal/access/current")
    @Operation(summary = "读取当前用户身份与权限", description = "只检查登录与账号有效；业务服务据此做本地授权")
    public ApiResponse<CurrentSubject> current() {
        return ApiResponse.success(identity.current());
    }
}
