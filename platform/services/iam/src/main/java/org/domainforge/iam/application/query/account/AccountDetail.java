package org.domainforge.iam.application.query.account;

import java.util.List;
import java.util.Set;

/**
 * 管理端账号详情：状态、角色分配与成员归属；不含凭据。
 *
 * @param account     账号投影
 * @param roleIds     分配的角色 ID
 * @param memberships 成员归属
 */
public record AccountDetail(AccountView account, Set<Long> roleIds, List<MembershipView> memberships) {
    /**
     * 复制集合，防止修改查询快照。
     */
    public AccountDetail {
        roleIds = Set.copyOf(roleIds);
        memberships = List.copyOf(memberships);
    }

    /**
     * 一项成员归属。
     */
    public record MembershipView(long unitId, boolean primary) {
    }
}
