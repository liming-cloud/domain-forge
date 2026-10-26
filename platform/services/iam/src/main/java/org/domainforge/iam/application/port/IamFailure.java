package org.domainforge.iam.application.port;

/**
 * 应用失败种类；协议层独立决定HTTP映射，不把数据库错误作为业务结果。
 */
public enum IamFailure {
    INVALID_REQUEST,
    UNAUTHENTICATED,
    FORBIDDEN,
    CONFLICT,
    RATE_LIMITED,
    UNAVAILABLE,
    UNKNOWN_RESULT
}
