package org.domainforge.gateway.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 值不存在或已存在满一个刷新间隔时才刷新；其他实例刚刷新过时跳过，一个间隔内只刷新一次。
 */
class SameTokenRotationTest {
    private static final long DAY = 86_400;
    private static final long HALF_HOUR = 1_800;

    @Test
    void refreshesOncePerInterval() {
        assertTrue(SameTokenRotation.due(-2, DAY, HALF_HOUR));
        assertTrue(SameTokenRotation.due(DAY - HALF_HOUR, DAY, HALF_HOUR));
        assertTrue(SameTokenRotation.due(DAY - HALF_HOUR + 20, DAY, HALF_HOUR));
        assertFalse(SameTokenRotation.due(DAY - 60, DAY, HALF_HOUR));
        assertFalse(SameTokenRotation.due(-1, DAY, HALF_HOUR));
    }
}
