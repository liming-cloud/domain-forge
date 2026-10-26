package org.domainforge.iam.application.port.authentication;

/**
 * 多实例共享的登录失败防护：按账号和直接来源地址限制尝试次数。
 *
 * <p>计数保存在 Redis；正常重连保留计数，Redis 数据丢失时不承诺保留旧计数。配置变更随重启生效。
 */
public interface LoginProtection {
    /**
     * 原子预占账号与来源地址额度；超限抛出限流失败，Redis 不可用时拒绝登录。
     */
    LoginReservation reserve(String normalizedName, String directAddress);

    /**
     * 登录成功只移除本次账号预占，不清除其他失败尝试，也不减少地址计数。
     */
    void succeeded(LoginReservation reservation);
}
