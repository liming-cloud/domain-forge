package org.domainforge.iam.infrastructure.configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 登录防护的有限部署输入；只有共享计数策略参与跨实例摘要。 */
@ConfigurationProperties(prefix = "iam.login-protection", ignoreUnknownFields = false)
public record LoginProtectionSettings(
    int accountAttempts,
    int accountWindowSeconds,
    int addressAttempts,
    int addressWindowSeconds,
    int hashWorkers,
    int hashQueueCapacity,
    int hashWaitMillis) {
  /** 全部范围只允许收紧保护或减少资源，缺失或越界直接阻止启动。 */
  public LoginProtectionSettings {
    range("account-attempts", accountAttempts, 1, 5);
    range("account-window-seconds", accountWindowSeconds, 600, 3600);
    range("address-attempts", addressAttempts, 1, 30);
    range("address-window-seconds", addressWindowSeconds, 60, 600);
    range("hash-workers", hashWorkers, 1, 2);
    range("hash-queue-capacity", hashQueueCapacity, 1, 8);
    range("hash-wait-millis", hashWaitMillis, 100, 1000);
  }

  /** 固定字段次序、单位和版本；本机哈希资源变化不改变共享策略身份。 */
  public String digest() {
    String canonical =
        "iam-login-policy-v1:"
            + accountAttempts
            + ":"
            + accountWindowSeconds
            + ":"
            + addressAttempts
            + ":"
            + addressWindowSeconds;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("运行环境缺少SHA-256", failure);
    }
  }

  /** Redis状态丢失至少等待全部当前窗口；SQL另保留已存在的更晚截止。 */
  public int recoverySeconds() {
    return Math.max(accountWindowSeconds, addressWindowSeconds);
  }

  private static void range(String name, int value, int minimum, int maximum) {
    if (value < minimum || value > maximum) throw new IllegalArgumentException("登录防护配置越界: " + name);
  }
}
