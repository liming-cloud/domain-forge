package org.domainforge.iam.interfaces.http.protocol;

/**
 * 修改名称。
 *
 * @param expectedVersion 读取时的版本
 * @param name            新名称
 */
public record RenameRequest(long expectedVersion, String name) {
    /**
     * 拒绝缺少必要字段。
     */
    public RenameRequest {
        if (expectedVersion < 1 || name == null) throw new IllegalArgumentException("请求字段不完整");
    }
}
