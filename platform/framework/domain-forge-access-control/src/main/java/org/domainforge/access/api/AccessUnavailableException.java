package org.domainforge.access.api;

import org.domainforge.shared.DependencyUnavailableException;

import java.io.Serial;

/**
 * 身份事实源或会话存储不可用，无法确认访问权限；不能降级为匿名或允许，也不能伪装成无权限。
 */
public final class AccessUnavailableException extends DependencyUnavailableException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息为固定说明。
     */
    public AccessUnavailableException() {
        super("身份服务暂不可用", null);
    }
}
