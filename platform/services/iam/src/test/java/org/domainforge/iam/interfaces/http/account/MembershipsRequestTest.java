package org.domainforge.iam.interfaces.http.account;

import org.domainforge.iam.application.command.account.MembershipInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.domainforge.iam.application.FailureAssertions.assertInvalidRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 成员归属请求转为应用输入：组织 ID 以字符串传入。
 */
class MembershipsRequestTest {
    @Test
    void convertsUnitIds() {
        var request = new MembershipsRequest(1, List.of(new AccountDetailResponse.MembershipBody("2", true)));
        assertEquals(List.of(new MembershipInput(2, true)), request.toInput());
    }

    // 非数字组织 ID 是非法输入（400），不是 500。
    @Test
    void nonNumericUnitIdIsInvalidRequest() {
        var request = new MembershipsRequest(1, List.of(new AccountDetailResponse.MembershipBody("x", true)));
        assertInvalidRequest(request::toInput);
    }
}
