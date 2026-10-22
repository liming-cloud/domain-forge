package org.domainforge.iam.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class LoginProtectionSettingsTest {
  // 原生Binder处理整数与范围；每项上下界都验证，不能以默认替代非法值。
  @Test
  void rejectsEveryUnsafeBoundaryAndFraction() {
    var defaults = defaults();
    int[][] ranges = {{1, 5}, {600, 3600}, {1, 30}, {60, 600}, {1, 2}, {1, 8}, {100, 1000}};
    int index = 0;
    for (String key : defaults.keySet()) {
      int[] range = ranges[index++];
      for (Object invalid : new Object[] {range[0] - 1, range[1] + 1, "1.5", "disabled"}) {
        var changed = new LinkedHashMap<>(defaults);
        changed.put(key, invalid);
        assertThrows(RuntimeException.class, () -> bind(changed), key);
      }
    }
    assertEquals(new LoginProtectionSettings(5, 600, 30, 60, 2, 8, 1000), bind(defaults));
  }

  // 本实例资源不改变共享摘要；四项共享参数任一改变均必须重新登记。
  @Test
  void sharedIdentityExcludesLocalResources() {
    var policy = bind(defaults());
    assertEquals(policy.digest(), new LoginProtectionSettings(5, 600, 30, 60, 1, 1, 100).digest());
    for (var changed :
        new LoginProtectionSettings[] {
          new LoginProtectionSettings(4, 600, 30, 60, 2, 8, 1000),
          new LoginProtectionSettings(5, 601, 30, 60, 2, 8, 1000),
          new LoginProtectionSettings(5, 600, 29, 60, 2, 8, 1000),
          new LoginProtectionSettings(5, 600, 30, 61, 2, 8, 1000)
        }) {
      assertNotEquals(policy.digest(), changed.digest());
    }
    assertEquals(3600, new LoginProtectionSettings(1, 3600, 1, 600, 1, 1, 100).recoverySeconds());
  }

  private static LoginProtectionSettings bind(Map<String, Object> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("iam.login-protection", Bindable.of(LoginProtectionSettings.class))
        .orElseThrow(IllegalStateException::new);
  }

  private static Map<String, Object> defaults() {
    var values = new LinkedHashMap<String, Object>();
    values.put("iam.login-protection.account-attempts", 5);
    values.put("iam.login-protection.account-window-seconds", 600);
    values.put("iam.login-protection.address-attempts", 30);
    values.put("iam.login-protection.address-window-seconds", 60);
    values.put("iam.login-protection.hash-workers", 2);
    values.put("iam.login-protection.hash-queue-capacity", 8);
    values.put("iam.login-protection.hash-wait-millis", 1000);
    return values;
  }
}
