package org.domainforge.web.api;

import org.domainforge.shared.ApiResponse;
import org.springframework.http.ResponseEntity;

/**
 * HTTP 状态工厂留在 Web 组件，共享结果只含数据与必要序列化声明。
 */
public final class HttpResponses {
    private HttpResponses() {
    }

    /**
     * 表达仅受理，固定为 HTTP 202，不承诺已经完成。
     *
     * @param data 受理信息
     * @param <T>  数据类型
     * @return 带受理状态的结果
     */
    public static <T> ResponseEntity<ApiResponse<T>> accepted(T data) {
        return ResponseEntity.accepted().body(new ApiResponse<>("ACCEPTED", "已受理", data));
    }
}
