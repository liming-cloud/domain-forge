package org.domainforge.iam.infrastructure.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginProtectionSettingsTest {
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

    // 原生Binder处理整数与范围；每项上下界都验证，不能以默认替代非法值。
    @Test
    void rejectsEveryUnsafeBoundaryAndFraction() {
        var defaults = defaults();
        int[][] ranges = {{1, 5}, {600, 3600}, {1, 30}, {60, 600}, {1, 2}, {1, 8}, {100, 1000}};
        int index = 0;
        for (String key : defaults.keySet()) {
            int[] range = ranges[index++];
            for (Object invalid : new Object[]{range[0] - 1, range[1] + 1, "1.5", "disabled"}) {
                var changed = new LinkedHashMap<>(defaults);
                changed.put(key, invalid);
                assertThrows(RuntimeException.class, () -> bind(changed), key);
            }
        }
        assertEquals(new LoginProtectionSettings(5, 600, 30, 60, 2, 8, 1000), bind(defaults));
    }
}
