package org.domainforge.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * HTTP 入口显式使用的三字段结果；不承担领域规则或状态判断。
 *
 * @param code    客户端判断的稳定代码
 * @param message 可安全展示的提示
 * @param data    本接口约定的数据，无数据仍序列化为 null
 * @param <T>     接口数据类型
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) T data) {
    private static final Set<String> RESERVED =
            Set.of(
                    "SUCCESS",
                    "ACCEPTED",
                    "INVALID_REQUEST",
                    "UNAUTHENTICATED",
                    "FORBIDDEN",
                    "SERVICE_UNAVAILABLE",
                    "INTERNAL_ERROR",
                    "RATE_LIMITED",
                    "GATEWAY_TIMEOUT",
                    "PAYLOAD_TOO_LARGE");

    /**
     * 拒绝缺失标识或提示；调用方负责提示中不包含秘密或内部异常。
     *
     * @param code    稳定代码
     * @param message 安全提示
     * @param data    接口数据
     */
    public ApiResponse {
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("响应代码和提示不能为空");
        }
    }

    /**
     * 表达已完成的成功；空查询可以传 null 或空集合。
     *
     * @param data 接口数据
     * @param <T>  数据类型
     * @return 成功结果
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "成功", data);
    }

    /**
     * 构造已判定的业务拒绝，不接受平台保留代码。
     *
     * @param code    业务稳定代码
     * @param message 消费方提供的安全提示
     * @return 无数据的拒绝结果，入口使用 HTTP 200
     */
    public static ApiResponse<Void> rejected(String code, String message) {
        if (RESERVED.contains(code)) {
            throw new IllegalArgumentException("业务代码不得占用平台标识");
        }
        return new ApiResponse<>(code, message, null);
    }
}
