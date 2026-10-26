package org.domainforge.iam.interfaces.http.catalog;

import java.util.List;
import java.util.Objects;

/**
 * 命名空间的全部权限，整体替换。
 *
 * @param permissions 权限列表
 */
public record InstallRequest(List<PermissionBody> permissions) {
    /**
     * 拒绝缺少必要字段。
     */
    public InstallRequest {
        if (permissions == null || permissions.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("请求字段不完整");
        permissions = List.copyOf(permissions);
    }
}
