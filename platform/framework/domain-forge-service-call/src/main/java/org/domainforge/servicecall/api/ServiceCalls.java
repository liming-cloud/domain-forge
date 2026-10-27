package org.domainforge.servicecall.api;

import org.domainforge.shared.ApiResponse;

/**
 * 读取被调方的三字段外壳：只有 SUCCESS 返回数据，其他代码是对方的业务拒绝。
 */
public final class ServiceCalls {
    private ServiceCalls() {
    }

    /**
     * 取成功结果中的数据。
     *
     * @param response 被调方响应
     * @param <T>      数据类型
     * @return 数据，可能为 null（对方成功但无数据）
     * @throws ServiceCallException 响应缺失为 UNAVAILABLE；非 SUCCESS 代码为 REJECTED，附带对方代码
     */
    public static <T> T data(ApiResponse<T> response) {
        if (response == null || response.code() == null) throw new ServiceCallException(ServiceCallFailure.UNAVAILABLE);
        if (!"SUCCESS".equals(response.code()))
            throw new ServiceCallException(ServiceCallFailure.REJECTED, response.code(), null);
        return response.data();
    }
}
