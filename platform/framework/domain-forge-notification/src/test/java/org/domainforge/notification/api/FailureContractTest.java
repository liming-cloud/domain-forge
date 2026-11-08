package org.domainforge.notification.api;

import org.domainforge.shared.BusinessRuleViolation.Kind;
import org.domainforge.shared.DependencyUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 短信与验证码拒绝带稳定错误码和类别，通知依赖故障是依赖不可用。
 */
class FailureContractTest {
    @Test
    void rejectionsCarryKindAndCode() {
        var phone = new SmsRejected(SmsRejected.Reason.INVALID_PHONE, "号码无效");
        assertEquals(Kind.UNPROCESSABLE, phone.kind());
        assertEquals("SMS_INVALID_PHONE", phone.code());
        var frequent = new VerificationRejected(VerificationRejected.Reason.TOO_FREQUENT, "发送过于频繁");
        assertEquals(Kind.RATE_LIMITED, frequent.kind());
        assertEquals("VERIFICATION_TOO_FREQUENT", frequent.code());
    }

    @Test
    void providerFailureIsDependencyUnavailable() {
        assertInstanceOf(DependencyUnavailableException.class, new NotificationUnavailableException("不可用", null));
    }
}
