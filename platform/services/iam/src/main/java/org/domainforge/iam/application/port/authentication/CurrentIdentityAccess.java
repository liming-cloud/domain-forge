package org.domainforge.iam.application.port.authentication;

/**
 * 敏感IAM命令取得本次已验证的私有身份，不把令牌摘要和凭据版本放入公共主体合同。
 */
public interface CurrentIdentityAccess {
    /**
     * 先经过公共访问执行链，再取得同令牌的私有快照；事务内仍重新校验当前资格。
     */
    CurrentIdentity current();

    /**
     * 当前请求原生解析的令牌仅供退出清理，不接收请求体自报的会话。
     */
    String token();
}
