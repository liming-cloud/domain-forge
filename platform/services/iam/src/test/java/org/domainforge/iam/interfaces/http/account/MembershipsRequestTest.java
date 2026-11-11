package org.domainforge.iam.interfaces.http.account;

import org.domainforge.iam.application.command.account.MembershipInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 成员归属请求转为应用输入：组织 ID 以字符串传入。
 */
class MembershipsRequestTest {
    @Test
    void convertsUnitIds() {
        var request = new MembershipsRequest(1, List.of(new AccountDetailResponse.MembershipBody("2", true)));
        assertEquals(List.of(new MembershipInput(2, true)), request.toInput());
    }

    // 现状：非数字组织 ID 抛 NumberFormatException，经协议层会成为 500；修复见 #138，本测试只锁定现状。
    @Test
    void nonNumericUnitIdCurrentlyEscapesAsNumberFormat() {
        var request = new MembershipsRequest(1, List.of(new AccountDetailResponse.MembershipBody("x", true)));
        assertThrows(NumberFormatException.class, request::toInput);
    }
}
