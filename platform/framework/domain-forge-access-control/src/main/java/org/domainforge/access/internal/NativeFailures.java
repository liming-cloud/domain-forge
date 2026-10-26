package org.domainforge.access.internal;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;

/**
 * 原生读取的统一失败边界：身份失效保留原生分类但去掉令牌，存储故障转为不可用，不改写授权判定。
 */
final class NativeFailures {
    private NativeFailures() {
    }

    /**
     * 把原生或依赖异常转换为对外安全的失败；已分类的拒绝原样返回。
     */
    static RuntimeException translate(RuntimeException failure) {
        if (failure instanceof NotLoginException login) return unauthenticated(login);
        if (failure instanceof NotPermissionException
                || failure instanceof NotRoleException
                || failure instanceof AccessException) return failure;
        return new AccessException(AccessFailure.UNAVAILABLE);
    }

    /**
     * 原生异常消息可能拼接实际令牌，重新构造只保留登录类型和失效类型。
     */
    static NotLoginException unauthenticated(NotLoginException failure) {
        var rejection = new NotLoginException("需要有效登录", failure.getLoginType(), failure.getType());
        rejection.setCode(failure.getCode());
        return rejection;
    }

    /**
     * 当前请求没有可用凭证或凭证已被事实源拒绝。
     */
    static NotLoginException missing() {
        return new NotLoginException("需要有效登录", "login", NotLoginException.INVALID_TOKEN);
    }
}
