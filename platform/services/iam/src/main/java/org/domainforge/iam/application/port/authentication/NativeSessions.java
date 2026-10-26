package org.domainforge.iam.application.port.authentication;

/**
 * 原生会话技术出口，只有SQL许可交集成立后才可对外确认身份。
 */
public interface NativeSessions {
    /**
     * 创建独立八小时令牌，禁止共享已有令牌或自动续期。
     */
    String issue(long accountId);

    /**
     * 验证当前原生状态，返回登录的账号 ID；会话失效返回 null，存储不可用必须报告故障。
     */
    Long subject(String token);

    /**
     * 清理指定原生令牌；SQL撤销事实不能依赖此操作成功。
     */
    void revoke(String token);

    /**
     * SQL已失效后的尽力资源清理；失败独立观测，不改变已确认业务结果。
     */
    void cleanup(String token);
}
