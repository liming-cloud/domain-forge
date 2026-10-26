package org.domainforge.iam.interfaces.http.access;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.iam.interfaces.security.ServiceCertificateIdentity;
import org.domainforge.shared.ApiResponse;
import org.domainforge.shared.CurrentSubject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部当前状态接口同时要求有效客户端服务证书和原生当前用户会话。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class InternalAccessController {
    private final CurrentAccess identity;
    private final ServiceCertificateIdentity certificates;

    /**
     * 本项目ID由部署配置绑定，不从客户端Header或请求参数读取。
     */
    public InternalAccessController(CurrentAccess identity, ServiceCertificateIdentity certificates) {
        this.identity = identity;
        this.certificates = certificates;
    }

    /**
     * 仅登记为本项目current-state调用方的服务可以读取，结果不包含令牌或凭据版本。
     */
    @SaCheckLogin
    @GetMapping("/internal/access/current")
    @Operation(summary = "内部当前身份检查", description = "必须使用登记服务的mTLS证书及原生Bearer凭证")
    public ApiResponse<CurrentSubject> current(HttpServletRequest request) {
        certificates.requireCurrentStateCaller(request);
        return ApiResponse.success(identity.current());
    }
}
