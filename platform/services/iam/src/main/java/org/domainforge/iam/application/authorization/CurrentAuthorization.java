package org.domainforge.iam.application.authorization;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.NativeSessions;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.authentication.SessionStore;
import org.springframework.stereotype.Service;

/**
 * 原生会话与SQL当前事实必须同时有效；管理事务再次校验SQL，不沿用入口权限。
 */
@Service
public final class CurrentAuthorization {
    private final NativeSessions nativeSessions;
    private final SessionStore sessions;
    private final SecretOperations secrets;

    /**
     * 两类状态按同一原生令牌摘要关联，不接受外部自报主体。
     */
    public CurrentAuthorization(
            NativeSessions nativeSessions, SessionStore sessions, SecretOperations secrets) {
        this.nativeSessions = nativeSessions;
        this.sessions = sessions;
        this.secrets = secrets;
    }

    private static IamException unauthenticated() {
        return new IamException(IamFailure.UNAUTHENTICATED, "需要有效登录");
    }

    /**
     * 供原生策略入口和内部当前状态协议使用，每次请求重新读取主库。
     */
    public CurrentIdentity current(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) throw unauthenticated();
        Long nativeSubject = nativeSessions.subject(token);
        if (nativeSubject == null) throw unauthenticated();
        CurrentIdentity current = require(secrets.digest(token), null);
        if (nativeSubject != current.accountId()) throw unauthenticated();
        return current;
    }

    /**
     * 管理命令在已取得守卫的事务里调用，权限来自本次SQL语句。
     */
    public CurrentIdentity require(String tokenDigest, String permission) {
        CurrentIdentity current = sessions.current(tokenDigest);
        if (current == null) throw unauthenticated();
        if (permission != null && !current.permissions().contains(permission))
            throw new IamException(IamFailure.FORBIDDEN, "无权执行该操作");
        return current;
    }

    /**
     * 系统专属管理操作：除权限外还要求操作者当前持有启用的系统管理员角色。
     */
    public CurrentIdentity requireSystem(String tokenDigest, String permission) {
        CurrentIdentity current = require(tokenDigest, permission);
        if (!current.systemAdministrator()) throw new IamException(IamFailure.FORBIDDEN, "仅系统管理员可执行该操作");
        return current;
    }
}
