package @@PACKAGE@@.bootstrap;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 暴露有界日志出口的当前降级状态；该状态不参与业务流量就绪判定。
 */
@Component("logging")
public class LoggingState implements HealthIndicator, MeterBinder {
    /**
     * 判断装配、队列饱和及原生出口停用；不能据此证明全部日志已送达。
     */
    boolean degraded() {
        AsyncAppender appender = appender();
        if (appender == null || !appender.isStarted() || appender.getRemainingCapacity() == 0) return true;
        var sinks = appender.iteratorForAppenders();
        return !sinks.hasNext() || !sinks.next().isStarted();
    }

    /**
     * 返回模板固定出口；不创建新的线程、队列或第二套日志系统。
     */
    private AsyncAppender appender() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var target = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC");
        return target instanceof AsyncAppender async ? async : null;
    }

    @Override
    public Health health() {
        return (degraded() ? Health.status("DEGRADED") : Health.up()).build();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("domainforge.logging.degraded", this, state -> state.degraded() ? 1 : 0)
                .description("日志出口不可用或队列已满；零值不证明没有历史丢失")
                .register(registry);
        Gauge.builder("domainforge.logging.queued", this,
                        state -> state.appender() == null ? 0 : state.appender().getNumberOfElementsInQueue())
                .register(registry);
    }
}
