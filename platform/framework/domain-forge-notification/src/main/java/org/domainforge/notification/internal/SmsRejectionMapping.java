package org.domainforge.notification.internal;

import org.domainforge.notification.api.SmsRejected;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.BusinessRejectionMapping;

/**
 * 短信被拒绝映射为业务拒绝：HTTP 200 与 {@code SMS_*} 稳定代码。
 */
public final class SmsRejectionMapping implements BusinessRejectionMapping<SmsRejected> {
    @Override
    public Class<SmsRejected> exceptionType() {
        return SmsRejected.class;
    }

    @Override
    public ApiResponse<Void> map(SmsRejected exception) {
        return ApiResponse.rejected(exception.reason().code(), exception.getMessage());
    }
}
