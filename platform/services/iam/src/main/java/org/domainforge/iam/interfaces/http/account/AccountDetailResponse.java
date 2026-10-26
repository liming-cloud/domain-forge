package org.domainforge.iam.interfaces.http.account;

import org.domainforge.iam.application.query.account.AccountDetail;
import org.domainforge.iam.interfaces.http.protocol.Ids;

import java.util.List;

/**
 * 账号详情的 HTTP 表示：角色 ID 与成员归属。
 */
public record AccountDetailResponse(
        AccountResponse account, List<String> roleIds, List<MembershipBody> memberships) {
    static AccountDetailResponse of(AccountDetail detail) {
        return new AccountDetailResponse(
                AccountResponse.of(detail.account()),
                Ids.strings(detail.roleIds()),
                detail.memberships().stream()
                        .map(item -> new MembershipBody(String.valueOf(item.unitId()), item.primary()))
                        .toList());
    }

    /**
     * 一项成员归属；请求与响应共用。
     *
     * @param unitId  组织 ID
     * @param primary 是否主部门
     */
    public record MembershipBody(String unitId, boolean primary) {
    }
}
