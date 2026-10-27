package org.domainforge.iam.infrastructure.configuration;

/**
 * 登录防护的次数与窗口；由配置组件绑定为动态配置，变更后对之后的登录立即生效。
 *
 * <p>取值范围只允许收紧保护：启动时越界阻止启动，运行中越界的发布不生效并保留当前值。
 */
public record LoginProtectionSettings(
        int accountAttempts, int accountWindowSeconds, int addressAttempts, int addressWindowSeconds) {
    /**
     * 缺失或越界直接拒绝。
     */
    public LoginProtectionSettings {
        range("account-attempts", accountAttempts, 1, 5);
        range("account-window-seconds", accountWindowSeconds, 600, 3600);
        range("address-attempts", addressAttempts, 1, 30);
        range("address-window-seconds", addressWindowSeconds, 60, 600);
    }

    static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException("登录防护配置越界: " + name);
    }
}
