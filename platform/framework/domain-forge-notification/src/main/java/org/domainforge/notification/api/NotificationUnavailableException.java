package org.domainforge.notification.api;

import org.domainforge.shared.DependencyUnavailableException;

/**
 * 通知依赖不可用：短信服务商不可达、超时、服务端错误或账号与模板配置错误，或验证码存储（Redis）不可用。
 * 组件映射为 HTTP 503，原因只写日志。
 */
public final class NotificationUnavailableException extends DependencyUnavailableException {
    /**
     * 原因只写入日志，不返回给客户端。
     */
    public NotificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
