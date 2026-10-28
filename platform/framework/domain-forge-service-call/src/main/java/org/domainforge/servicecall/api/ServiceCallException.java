package org.domainforge.servicecall.api;

/**
 * 跨服务调用的失败。消息为固定文本，不包含对方响应内容、地址或令牌。
 */
public final class ServiceCallException extends RuntimeException {
    private final ServiceCallFailure failure;
    private final String code;

    /**
     * 技术失败或身份失败，没有对方代码。
     *
     * @param failure 分类
     */
    public ServiceCallException(ServiceCallFailure failure) {
        this(failure, null, null);
    }

    /**
     * 带对方代码与原因的失败；原因只用于诊断日志。
     *
     * @param failure 分类
     * @param code    对方响应中的稳定代码，可为空
     * @param cause   原因，可为空
     */
    public ServiceCallException(ServiceCallFailure failure, String code, Throwable cause) {
        super(switch (failure) {
            case UNAUTHENTICATED -> "被调服务要求身份认证";
            case FORBIDDEN -> "被调服务拒绝访问";
            case REJECTED -> "被调服务拒绝了请求";
            case UNAVAILABLE -> "被调服务暂不可用";
            case UNKNOWN -> "被调服务的处理结果未知";
        }, cause);
        this.failure = failure;
        this.code = code;
    }

    /**
     * 稳定分类。
     */
    public ServiceCallFailure failure() {
        return failure;
    }

    /**
     * 对方的稳定代码（REJECTED 时可用），其他情况可为空。
     */
    public String code() {
        return code;
    }
}
