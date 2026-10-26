package org.domainforge.iam.interfaces.http.protocol;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 明确启用或停用；缺少 enabled 不能被解释为停用。
 *
 * @param expectedVersion 读取时的版本
 * @param enabled         目标状态
 */
@Schema(description = "明确启用或停用；缺少enabled不能被解释为停用")
public record StateRequest(long expectedVersion, Boolean enabled) {
    /**
     * 拒绝缺少命令必要字段；不会从空值推断写入意图。
     */
    public StateRequest {
        if (expectedVersion < 1 || enabled == null) throw new IllegalArgumentException("请求字段不完整");
    }
}
