package org.domainforge.access.internal;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.web.api.FailureTranslator;
import org.jspecify.annotations.Nullable;

/**
 * 把 Sa-Token 原生的登录、权限、角色异常转为平台访问拒绝，由 web-support 统一分派；
 * 原生异常消息可能拼接实际令牌，转换后只保留固定提示。通过 {@code META-INF/services} 登记。
 */
public final class NativeFailureTranslator implements FailureTranslator {
    @Override
    public @Nullable RuntimeException translate(Exception failure) {
        if (failure instanceof NotLoginException) return new AccessException(AccessFailure.UNAUTHENTICATED);
        if (failure instanceof NotPermissionException || failure instanceof NotRoleException)
            return new AccessException(AccessFailure.FORBIDDEN);
        return null;
    }
}
