package org.domainforge.notification.internal;

import org.domainforge.notification.api.SmsRejected;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 号码规范化与日志掩码。
 */
class PhoneNumbersTest {
    @Test
    void normalizesMainlandAndInternational() {
        assertEquals("13800138000", PhoneNumbers.normalize("13800138000"));
        assertEquals("13800138000", PhoneNumbers.normalize("+86 138-0013-8000"));
        assertEquals("13800138000", PhoneNumbers.normalize("008613800138000"));
        assertEquals("85291234567", PhoneNumbers.normalize("+852 9123 4567"));
        assertEquals("14155550100", PhoneNumbers.normalize("0014155550100"));
    }

    @Test
    void rejectsMalformed() {
        for (String invalid : new String[]{null, "", "12345", "1380013800", "23800138000", "+0123456789", "abc"})
            assertEquals(SmsRejected.Reason.INVALID_PHONE,
                    assertThrows(SmsRejected.class, () -> PhoneNumbers.normalize(invalid)).reason());
    }

    @Test
    void masksAllButLastFour() {
        assertEquals("*******8000", PhoneNumbers.mask("13800138000"));
        assertEquals("****", PhoneNumbers.mask("12"));
    }
}
