package org.domainforge.iam.interfaces.http.organization;

/**
 * 在父组织下创建组织。
 *
 * @param parentId 父组织 ID
 * @param name     名称
 */
public record CreateUnitRequest(Long parentId, String name) {
    /**
     * 拒绝缺少必要字段。
     */
    public CreateUnitRequest {
        if (parentId == null || name == null) throw new IllegalArgumentException("请求字段不完整");
    }
}
