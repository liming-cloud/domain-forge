package org.domainforge.access.api;

/**
 * 公开访问失败语义不依赖HTTP状态或原生框架异常；身份服务不可用另见 {@link AccessUnavailableException}。
 */
public enum AccessFailure {
    UNAUTHENTICATED,
    FORBIDDEN
}
