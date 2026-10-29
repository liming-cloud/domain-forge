package org.domainforge.notification.internal;

import org.domainforge.notification.api.NotificationUnavailableException;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.HttpFailureResponses;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 通知依赖不可用（短信服务商、验证码存储）映射为 503；原因已由发送方记录日志。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class NotificationFailureAdvice {
    /**
     * 依赖故障，不改写为业务拒绝。
     */
    @ExceptionHandler(NotificationUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return HttpFailureResponses.unavailable();
    }
}
