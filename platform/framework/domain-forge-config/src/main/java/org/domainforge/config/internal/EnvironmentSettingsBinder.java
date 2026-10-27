package org.domainforge.config.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.config.api.DynamicSettings;
import org.domainforge.config.api.DynamicSettingsBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在 Spring Cloud 的配置变化事件之后重新绑定受影响的配置组，并以指标记录每次应用结果。
 *
 * <p>无法应用的发布值不生效：整组保留当前快照，记录错误日志与 {@code rejected} 计数（O-09）。
 */
public final class EnvironmentSettingsBinder
        implements DynamicSettingsBinder, ApplicationListener<EnvironmentChangeEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentSettingsBinder.class);

    private final Environment environment;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final List<BoundSettings<?>> groups = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicLong> appliedAt = new ConcurrentHashMap<>();

    /**
     * 指标注册表可为空，此时只记录日志。
     */
    public EnvironmentSettingsBinder(Environment environment, MeterRegistry metrics, Clock clock) {
        this.environment = environment;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public <T> DynamicSettings<T> bind(String prefix, Class<T> type) {
        var settings = new BoundSettings<>(prefix, type, () -> Binder.get(environment));
        groups.add(settings);
        applied(prefix);
        return settings;
    }

    /**
     * 只处理涉及本组前缀的变化；各组独立，一组失败不影响其他组。
     */
    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        for (BoundSettings<?> settings : groups) {
            if (!settings.affectedBy(event.getKeys())) continue;
            try {
                settings.refresh();
                applied(settings.prefix());
                LOG.info("config_applied group={}", settings.prefix());
            } catch (RuntimeException failure) {
                count(settings.prefix(), "rejected");
                LOG.error("config_rejected group={} reason={}", settings.prefix(), failure.getMessage());
            }
        }
    }

    private void applied(String prefix) {
        count(prefix, "applied");
        AtomicLong time = appliedAt.computeIfAbsent(prefix, this::appliedGauge);
        time.set(clock.instant().getEpochSecond());
    }

    private AtomicLong appliedGauge(String prefix) {
        var time = new AtomicLong();
        if (metrics != null) {
            Gauge.builder("domainforge.config.applied.time", time, AtomicLong::get)
                    .tag("group", prefix)
                    .baseUnit("seconds")
                    .register(metrics);
        }
        return time;
    }

    private void count(String prefix, String result) {
        if (metrics == null) return;
        Counter.builder("domainforge.config.refresh")
                .tag("group", prefix)
                .tag("result", result)
                .register(metrics)
                .increment();
    }
}
