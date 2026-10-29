package org.domainforge.notification.internal;

import org.domainforge.notification.api.VerificationRejected;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.BusinessRejectionMapping;

/**
 * 验证码发送受限映射为业务拒绝：HTTP 200 与 {@code VERIFICATION_*} 稳定代码。
 */
public final class VerificationRejectionMapping implements BusinessRejectionMapping<VerificationRejected> {
    @Override
    public Class<VerificationRejected> exceptionType() {
        return VerificationRejected.class;
    }

    @Override
    public ApiResponse<Void> map(VerificationRejected exception) {
        return ApiResponse.rejected(exception.reason().code(), exception.getMessage());
    }
}
