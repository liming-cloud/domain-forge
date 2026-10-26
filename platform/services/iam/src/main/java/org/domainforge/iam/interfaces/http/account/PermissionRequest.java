package org.domainforge.iam.interfaces.http.account;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;
import java.util.UUID;

/**
 * 目标账号权限变更；版本必需且只接受有限权限集合。
 */
@Schema(description = "目标账号权限变更；版本必需且只接受有限权限集合")
public record PermissionRequest(UUID operationId, long expectedVersion, Set<String> permissions) {
    /**
     * 拒绝缺少命令必要字段；不会从空值推断写入意图。
     */
    public PermissionRequest {
        if (operationId == null || expectedVersion < 1 || permissions == null)
            throw new IllegalArgumentException("请求字段不完整");
        permissions = Set.copyOf(permissions);
    }
}
