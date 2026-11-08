package org.domainforge.iam.interfaces.http.role;

import org.domainforge.iam.application.command.role.GrantInput;

import java.util.List;
import java.util.Objects;

/**
 * 整体替换角色授予。
 *
 * @param expectedVersion 角色版本
 * @param grants          全部授予
 */
public record GrantsRequest(long expectedVersion, List<GrantBody> grants) {
    /**
     * 拒绝缺少必要字段。
     */
    public GrantsRequest {
        if (expectedVersion < 1 || grants == null || grants.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("请求字段不完整");
        grants = List.copyOf(grants);
    }

    List<GrantInput> toInput() {
        return grants.stream().map(GrantBody::toInput).toList();
    }
}
