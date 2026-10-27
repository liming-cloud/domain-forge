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
    private static <T> T bind(String prefix, Class<T> type, Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind(prefix, Bindable.of(type))
                .orElseThrow(IllegalStateException::new);
    }

    private static Map<String, Object> limits() {
        var values = new LinkedHashMap<String, Object>();
        values.put("iam.login-protection.account-attempts", 5);
        values.put("iam.login-protection.account-window-seconds", 600);
        values.put("iam.login-protection.address-attempts", 30);
        values.put("iam.login-protection.address-window-seconds", 60);
        return values;
    }

    private static Map<String, Object> hashing() {
        var values = new LinkedHashMap<String, Object>();
        values.put("iam.password-hashing.workers", 2);
        values.put("iam.password-hashing.queue-capacity", 8);
        values.put("iam.password-hashing.wait-millis", 1000);
        return values;
    }

    private static void rejectsBoundaries(String prefix, Class<?> type, Map<String, Object> valid, int[][] ranges) {
        int index = 0;
        for (String key : valid.keySet()) {
            int[] range = ranges[index++];
            for (Object invalid : new Object[]{range[0] - 1, range[1] + 1, "1.5", "disabled"}) {
                var changed = new LinkedHashMap<>(valid);
                changed.put(key, invalid);
                assertThrows(RuntimeException.class, () -> bind(prefix, type, changed), key);
            }
        }
    }

    // 原生Binder处理整数与范围；每项上下界都验证，不能以默认替代非法值。
    @Test
    void loginLimitsRejectEveryUnsafeBoundaryAndFraction() {
        rejectsBoundaries("iam.login-protection", LoginProtectionSettings.class, limits(),
                new int[][]{{1, 5}, {600, 3600}, {1, 30}, {60, 600}});
        assertEquals(new LoginProtectionSettings(5, 600, 30, 60),
                bind("iam.login-protection", LoginProtectionSettings.class, limits()));
    }

    @Test
    void passwordHashingRejectsEveryUnsafeBoundaryAndFraction() {
        rejectsBoundaries("iam.password-hashing", PasswordHashingSettings.class, hashing(),
                new int[][]{{1, 2}, {1, 8}, {100, 1000}});
        assertEquals(new PasswordHashingSettings(2, 8, 1000),
                bind("iam.password-hashing", PasswordHashingSettings.class, hashing()));
    }
}
