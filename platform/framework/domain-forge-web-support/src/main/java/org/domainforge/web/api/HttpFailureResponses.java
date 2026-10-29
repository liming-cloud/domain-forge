package org.domainforge.web.api;

import org.domainforge.shared.ApiResponse;
import org.springframework.http.ResponseEntity;

/**
 * 固定技术失败的 HTTP 状态与安全提示；不执行认证、授权或服务可用性判断。
 */
public final class HttpFailureResponses {
    /**
     * 静态响应工厂不允许实例化。
     */
    private HttpFailureResponses() {
    }

    /**
     * 请求不合法的 HTTP 400 结果，不回传解析详情。
     *
     * @return 请求不合法的 HTTP 400 结果，不回传解析详情
     */
    public static ResponseEntity<ApiResponse<Void>> invalidRequest() {
        return failure(400, "INVALID_REQUEST", "请求不合法");
    }

    /**
     * 尚未认证的 HTTP 401 结果。
     *
     * @return 尚未认证的 HTTP 401 结果
     */
    public static ResponseEntity<ApiResponse<Void>> unauthenticated() {
        return failure(401, "UNAUTHENTICATED", "需要身份认证");
    }

    /**
     * 无权执行的 HTTP 403 结果。
     *
     * @return 无权执行的 HTTP 403 结果
     */
    public static ResponseEntity<ApiResponse<Void>> forbidden() {
        return failure(403, "FORBIDDEN", "无权执行此操作");
    }

    /**
     * 请求体超过上限的 HTTP 413 结果（如上传超过 multipart 上限）。
     *
     * @return 请求体超过上限的 HTTP 413 结果
     */
    public static ResponseEntity<ApiResponse<Void>> payloadTooLarge() {
        return failure(413, "PAYLOAD_TOO_LARGE", "请求内容过大");
    }

    /**
     * 已明确不可用的 HTTP 503 结果。
     *
     * @return 已明确不可用的 HTTP 503 结果
     */
    public static ResponseEntity<ApiResponse<Void>> unavailable() {
        return failure(503, "SERVICE_UNAVAILABLE", "服务暂不可用");
    }

    /**
     * 未知故障的 HTTP 500 结果，不含异常消息。
     *
     * @return 未知故障的 HTTP 500 结果，不含异常消息
     */
    public static ResponseEntity<ApiResponse<Void>> internalError() {
        return failure(500, "INTERNAL_ERROR", "服务处理失败");
    }

    /**
     * 使用本类限定的分类构造无数据结果。
     *
     * @param status  HTTP 失败状态
     * @param code    平台稳定代码
     * @param message 固定安全提示
     * @return 固定状态的响应
     */
    private static ResponseEntity<ApiResponse<Void>> failure(
            int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiResponse<>(code, message, null));
    }
}
