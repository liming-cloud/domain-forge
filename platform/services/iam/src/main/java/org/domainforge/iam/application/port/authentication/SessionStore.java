package org.domainforge.iam.application.port.authentication;

/**
 * 会话许可和当前账号的SQL交集，不信任只存在于Redis的令牌。
 */
public interface SessionStore {
    /**
     * 仅登录确认账号版本后插入，不允许用读取路径补建许可。
     */
    void insert(SessionGrant grant);

    /**
     * 用本次新语句读取主库状态；无有效交集返回null。
     */
    CurrentIdentity current(String tokenDigest);

    /**
     * 撤销当前许可，重复撤销不使许可复活。
     */
    void revoke(String tokenDigest);
}
