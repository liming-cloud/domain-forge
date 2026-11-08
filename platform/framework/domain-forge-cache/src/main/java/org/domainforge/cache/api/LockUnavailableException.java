package org.domainforge.cache.api;

import org.domainforge.shared.DependencyUnavailableException;

/**
 * 在等待时间内未取得锁，或锁服务不可用；业务未执行。
 */
public final class LockUnavailableException extends DependencyUnavailableException {
    /**
     * 消息为固定说明。
     */
    public LockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
