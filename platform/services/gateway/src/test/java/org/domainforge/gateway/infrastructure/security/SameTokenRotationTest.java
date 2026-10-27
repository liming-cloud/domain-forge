package org.domainforge.gateway.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只在值不存在或剩余有效期不足一半时轮换，多个实例先后检查不会连续轮换。
 */
class SameTokenRotationTest {
    @Test
    void rotatesOnlyWhenMissingOrPastHalfLife() {
        assertTrue(SameTokenRotation.due(-2, 86_400));
        assertTrue(SameTokenRotation.due(43_199, 86_400));
        assertFalse(SameTokenRotation.due(43_200, 86_400));
        assertFalse(SameTokenRotation.due(86_400, 86_400));
        assertFalse(SameTokenRotation.due(-1, 86_400));
    }
}
