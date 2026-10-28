package org.domainforge.servicecall.api;

/**
 * 跨服务调用失败的稳定分类；调用方按自己的合同翻译，不把技术失败当成业务结果。
 */
public enum ServiceCallFailure {
    /**
     * 被调方判定未认证（401）：用户令牌缺失或已失效，或 Same-Token 不符。
     */
    UNAUTHENTICATED,
    /**
     * 被调方判定无权限（403）。
     */
    FORBIDDEN,
    /**
     * 被调方已作出业务拒绝或判定请求不合法：200 外壳中的非成功代码，或 4xx 响应；{@link ServiceCallException#code()} 为对方代码。
     */
    REJECTED,
    /**
     * 请求确定没有被处理：连接失败、无可用实例、5xx（504 除外）、响应不符合合同。
     */
    UNAVAILABLE,
    /**
     * 请求已发出但没有得到有效结果（读取超时、连接中断、504）：远端可能已经执行，不能当作失败或成功。
     */
    UNKNOWN
}
