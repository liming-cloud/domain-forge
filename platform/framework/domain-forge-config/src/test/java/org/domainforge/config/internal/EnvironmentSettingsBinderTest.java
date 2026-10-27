package org.domainforge.config.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 以真实 Environment 与 Spring Cloud 变化事件验证整组绑定、失败保留与指标。
 */
class EnvironmentSettingsBinderTest {
    record Limits(int attempts, int windowSeconds) {
        Limits {
            if (attempts < 1 || attempts > 5) throw new IllegalArgumentException("attempts 越界");
        }
    }

    private final Map<String, Object> values = new HashMap<>();
    private final StandardEnvironment environment = new StandardEnvironment();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private EnvironmentSettingsBinder binder;

    @BeforeEach
    void setUp() {
        values.put("demo.limits.attempts", "3");
        values.put("demo.limits.window-seconds", "60");
        environment.getPropertySources().addFirst(new MapPropertySource("nacos", values));
        binder = new EnvironmentSettingsBinder(environment, metrics, Clock.systemUTC());
    }

    private void change(String key, String value) {
        values.put(key, value);
        binder.onApplicationEvent(new EnvironmentChangeEvent(environment, Set.of(key)));
    }

    private double refreshes(String result) {
        var counter = metrics.find("domainforge.config.refresh").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void bindsAtStartupAndAppliesValidChanges() {
        var limits = binder.bind("demo.limits", Limits.class);
        assertEquals(new Limits(3, 60), limits.current());
        change("demo.limits.attempts", "2");
        assertEquals(new Limits(2, 60), limits.current());
        assertEquals(2, refreshes("applied"));
    }

    @Test
    void unapplicableValueKeepsCurrentSnapshotUntilCorrected() {
        var limits = binder.bind("demo.limits", Limits.class);
        Limits before = limits.current();
        change("demo.limits.attempts", "99");
        assertSame(before, limits.current());
        change("demo.limits.attempts", "abc");
        assertSame(before, limits.current());
        assertEquals(2, refreshes("rejected"));
        change("demo.limits.attempts", "4");
        assertEquals(new Limits(4, 60), limits.current());
    }

    @Test
    void unrelatedChangesDoNotRebind() {
        var limits = binder.bind("demo.limits", Limits.class);
        Limits before = limits.current();
        change("demo.other.attempts", "99");
        assertSame(before, limits.current());
        assertEquals(1, refreshes("applied"));
    }

    @Test
    void startupRejectsMissingInvalidOrUnknownSettings() {
        assertThrows(IllegalStateException.class, () -> binder.bind("demo.absent", Limits.class));
        values.put("demo.limits.attempts", "0");
        assertThrows(RuntimeException.class, () -> binder.bind("demo.limits", Limits.class));
        values.put("demo.limits.attempts", "3");
        values.put("demo.limits.attempt", "3");
        assertThrows(RuntimeException.class, () -> binder.bind("demo.limits", Limits.class));
    }

    @Test
    void relaxedKeyNamesStillMatchTheGroup() {
        var limits = binder.bind("demo.limits", Limits.class);
        values.put("demo.limits.window-seconds", "120");
        binder.onApplicationEvent(new EnvironmentChangeEvent(environment, Set.of("demo.limits.windowSeconds")));
        assertEquals(120, limits.current().windowSeconds());
    }
}
