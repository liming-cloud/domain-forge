package org.domainforge.iam.interfaces.http.role;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.domainforge.iam.application.FailureAssertions.assertInvalidRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 授予请求转为应用输入：组织 ID 以十进制字符串传入。
 */
class GrantBodyTest {
    @Test
    void convertsUnitIds() {
        assertEquals(Set.of(2L, 3L), new GrantBody("p", "CUSTOM", List.of("2", "3")).toInput().unitIds());
    }

    // 非数字的组织 ID 是非法输入（400），不是 500。
    @Test
    void malformedUnitIdIsInvalidRequest() {
        assertInvalidRequest(() -> new GrantBody("p", "CUSTOM", List.of("x")).toInput());
    }
}
