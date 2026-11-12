package org.domainforge.iam.interfaces.http.account;

import org.domainforge.iam.application.command.account.MembershipInput;
import org.domainforge.iam.interfaces.http.protocol.Ids;

import java.util.List;
import java.util.Objects;

/**
 * 整体替换账号的成员归属；存在归属时恰有一个主部门。
 *
 * @param expectedVersion 账号版本
 * @param memberships     成员归属
 */
public record MembershipsRequest(long expectedVersion, List<AccountDetailResponse.MembershipBody> memberships) {
    /**
     * 拒绝缺少必要字段。
     */
    public MembershipsRequest {
        if (expectedVersion < 1 || memberships == null || memberships.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("请求字段不完整");
        memberships = List.copyOf(memberships);
    }

    /**
     * 转为应用输入。
     */
    List<MembershipInput> toInput() {
        return memberships.stream().map(item -> new MembershipInput(Ids.parse(item.unitId()), item.primary()))
                .toList();
    }
}
