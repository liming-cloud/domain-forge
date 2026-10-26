package org.domainforge.access.internal;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.CurrentSubject;

import java.util.List;

/**
 * 原生权限和角色数据扩展点；只提供本请求已完整验证的同一主体，不查询其他账号。
 */
public final class CurrentSubjectPermissions implements StpInterface {
    private final CurrentAccess current;
    private final StpLogic logic;

    /**
     * 复用请求快照；原生 AND／OR 判定仍由 Sa-Token 执行。
     */
    public CurrentSubjectPermissions(CurrentAccess current, StpLogic logic) {
        this.current = current;
        this.logic = logic;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.copyOf(subject(loginId, loginType).permissions());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.copyOf(subject(loginId, loginType).roles());
    }

    /**
     * 原生参数不能被用于借本请求身份查询其他主体或登录类型；程序式调用同样经过原生读取边界。
     */
    private CurrentSubject subject(Object loginId, String loginType) {
        CurrentSubject subject = current.current();
        String account = String.valueOf(subject.accountId());
        Object nativeLoginId;
        try {
            nativeLoginId = logic.getLoginId();
        } catch (RuntimeException failure) {
            throw NativeFailures.translate(failure);
        }
        if (!logic.getLoginType().equals(loginType)
                || !account.equals(String.valueOf(loginId))
                || !account.equals(String.valueOf(nativeLoginId)))
            throw new AccessException(AccessFailure.FORBIDDEN);
        return subject;
    }
}
