package org.domainforge.access.api;

/**
 * 公开访问失败语义不依赖HTTP状态或原生框架异常。
 */
public enum AccessFailure {
    UNAUTHENTICATED,
    FORBIDDEN,
    UNAVAILABLE
}
