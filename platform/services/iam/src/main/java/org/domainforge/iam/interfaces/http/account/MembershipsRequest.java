package org.domainforge.iam.interfaces.http.account;

import org.domainforge.iam.domain.model.account.Membership;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
     * 转为领域值对象；重复组织在领域规则中拒绝。
     */
    Set<Membership> toDomain() {
        Set<Membership> values = new HashSet<>();
        for (var item : memberships) {
            if (!values.add(new Membership(Long.parseLong(item.unitId()), item.primary())))
                throw new IllegalArgumentException("成员归属重复");
        }
        return values;
    }
}
