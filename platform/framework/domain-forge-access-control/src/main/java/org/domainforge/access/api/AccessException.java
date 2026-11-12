package org.domainforge.access.api;

import org.domainforge.shared.BusinessRuleViolation;

import java.io.Serial;

/**
 * 明确的访问拒绝：未认证为 401，无权限为 403；错误消息不携带传输细节或秘密。
 */
public final class AccessException extends BusinessRuleViolation {
    @Serial
    private static final long serialVersionUID = 1L;

    private final AccessFailure failure;

    /**
     * 外部依赖错误不能伪装成无身份，那属于 {@link AccessUnavailableException}。
     */
    public AccessException(AccessFailure failure) {
        super(
                switch (failure) {
                    case UNAUTHENTICATED -> Kind.UNAUTHENTICATED;
                    case FORBIDDEN -> Kind.FORBIDDEN;
                },
                failure.name(),
                switch (failure) {
                    case UNAUTHENTICATED -> "需要有效登录";
                    case FORBIDDEN -> "无权执行该操作";
                });
        this.failure = failure;
    }

    /**
     * 返回稳定分类，消费者按自己的协议映射。
     */
    public AccessFailure failure() {
        return failure;
    }
}
