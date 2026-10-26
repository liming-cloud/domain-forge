package org.domainforge.iam.interfaces.http.role;

/**
 * 创建角色。
 *
 * @param code 角色代码，创建后不可修改
 * @param name 名称
 */
public record CreateRoleRequest(String code, String name) {
    /**
     * 拒绝缺少必要字段。
     */
    public CreateRoleRequest {
        if (code == null || name == null) throw new IllegalArgumentException("请求字段不完整");
    }
}
