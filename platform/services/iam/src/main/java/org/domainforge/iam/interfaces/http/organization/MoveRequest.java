package org.domainforge.iam.interfaces.http.organization;

/**
 * 移动到新父节点；组织必须给出父节点，菜单的 null 表示顶层。
 *
 * @param expectedVersion 读取时的版本
 * @param parentId        新父节点 ID
 */
public record MoveRequest(long expectedVersion, Long parentId) {
    /**
     * 拒绝缺少版本。
     */
    public MoveRequest {
        if (expectedVersion < 1) throw new IllegalArgumentException("请求字段不完整");
    }
}
