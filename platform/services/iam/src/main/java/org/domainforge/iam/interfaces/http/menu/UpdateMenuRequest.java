package org.domainforge.iam.interfaces.http.menu;

/**
 * 修改菜单项的展示属性与关联权限。
 */
public record UpdateMenuRequest(long expectedVersion, String name, String path, Integer sortOrder, String permission) {
    /**
     * 拒绝缺少必要字段。
     */
    public UpdateMenuRequest {
        if (expectedVersion < 1 || name == null || sortOrder == null) throw new IllegalArgumentException("请求字段不完整");
    }
}
