package org.domainforge.iam.interfaces.http.account;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 明确启用或禁用；缺少enabled不能被解释为禁用。
 */
@Schema(description = "明确启用或禁用；缺少enabled不能被解释为禁用")
public record AccountStateRequest(UUID operationId, long expectedVersion, Boolean enabled) {
    /**
     * 拒绝缺少命令必要字段；不会从空值推断写入意图。
     */
    public AccountStateRequest {
        if (operationId == null || expectedVersion < 1 || enabled == null)
            throw new IllegalArgumentException("请求字段不完整");
    }
}
