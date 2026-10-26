package org.domainforge.iam.interfaces.http.account;

import java.util.List;
import java.util.Objects;

/**
 * 整体替换账号的角色分配。
 *
 * @param expectedVersion 账号版本
 * @param roleIds         角色 ID（十进制字符串或数字）
 */
public record RolesRequest(long expectedVersion, List<Long> roleIds) {
    /**
     * 拒绝缺少必要字段。
     */
    public RolesRequest {
        if (expectedVersion < 1 || roleIds == null || roleIds.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("请求字段不完整");
        roleIds = List.copyOf(roleIds);
    }
}
