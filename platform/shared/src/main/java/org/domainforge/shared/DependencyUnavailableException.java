package org.domainforge.shared;

import java.io.Serial;

/**
 * 依赖不可用的基类：缓存、锁、存储、短信、被调服务等无法完成请求，业务未执行或结果无法确认。
 *
 * <p>Web 入口统一返回 503；消息与原因只写日志，不返回给客户端。调用方不能把它当作未命中、成功或业务拒绝。
 */
public abstract class DependencyUnavailableException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息为固定说明，不含键、值、地址或令牌。
     *
     * @param message 固定说明
     * @param cause   原因，可为空
     */
    protected DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
