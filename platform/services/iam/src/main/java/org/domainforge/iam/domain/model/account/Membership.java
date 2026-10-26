package org.domainforge.iam.domain.model.account;

import org.domainforge.shared.ValueObject;

/**
 * 账号对一个组织的成员归属；主部门只作默认展示，不改变数据范围起点。
 *
 * @param unitId  组织 ID
 * @param primary 是否为主部门
 */
public record Membership(long unitId, boolean primary) implements ValueObject {
    /**
     * 组织 ID 必须为正数。
     */
    public Membership {
        if (unitId <= 0) throw new IllegalArgumentException("组织 ID 无效");
    }
}
