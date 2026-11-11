package org.domainforge.messaging.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox 中继：定时（以及事务提交后被唤醒时）短事务认领到期记录的租约、在事务外发送、再以短事务记录结果。
 *
 * <ul>
 *   <li>发送期间不持有数据库连接与行锁：RocketMQ 变慢时不拖住同库的业务事务。</li>
 *   <li>多实例以 {@code FOR UPDATE SKIP LOCKED} 认领、以租约（固定 60 秒）分担；有顺序键的记录只取该键最早一条（由认领 SQL 保证），失败时同键后续不发，保持顺序。</li>
 *   <li>结果按记录 ID 与本批令牌写回：租约过期被其他实例接手后，原实例迟到的结果不覆盖。</li>
 *   <li>一批中出现发送失败即停止本批并释放未发送记录的租约：多半是 RocketMQ 不可用，不逐条等超时。</li>
 *   <li>失败按 2 的幂退避（上限 5 分钟），永不丢弃；积压以指标 {@code domainforge.messaging.outbox.pending} 观察。</li>
 *   <li>发送成功、记录结果之前中断，或发送超过租约被其他实例接手，会重发同一事件 ID：至少一次，由消费方去重。</li>
 * </ul>
 */
public final class OutboxRelay implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long MAX_DELAY_SECONDS = 300;
    private static final long LEASE_SECONDS = 60;

    private final OutboxMapper outbox;
    private final InboxMapper inbox;
    private final TransactionOperations transactions;
    private final Producer producer;
    private final Settings settings;
    private final TraceContexts traces;
    private final Counter sent;
    private final Counter failed;
    private final AtomicBoolean waking = new AtomicBoolean();
    private volatile ScheduledExecutorService executor;

    /**
     * 中继参数。
     *
     * @param interval       定时扫描间隔
     * @param batchSize      每批抢占条数
     * @param retentionHours 已发送记录与消费去重记录的保留时长
     * @param publishing     本服务发布事件（有 Outbox 表与生产者）：启用发送与已发送记录清理
     * @param consuming      本服务消费事件（有 Inbox 表）：启用去重记录清理
     */
    public record Settings(Duration interval, int batchSize, long retentionHours, boolean publishing,
            boolean consuming) {
    }

    /**
     * 生产者由组件装配，只消费的服务为空；关闭顺序由 Spring 生命周期保证（先停中继再关客户端）。
     */
    public OutboxRelay(OutboxMapper outbox, InboxMapper inbox, TransactionOperations transactions, Producer producer,
            Settings settings, MeterRegistry metrics, TraceContexts traces) {
        this.outbox = outbox;
        this.inbox = inbox;
        this.transactions = transactions;
        this.producer = producer;
        this.settings = settings;
        this.traces = traces;
        this.sent = Counter.builder("domainforge.messaging.outbox.sent").register(metrics);
        this.failed = Counter.builder("domainforge.messaging.outbox.failed").register(metrics);
        if (settings.publishing())
            Gauge.builder("domainforge.messaging.outbox.pending", this, OutboxRelay::pendingOrNaN).register(metrics);
    }

    /**
     * 提交后唤醒：合并多次唤醒为一次立即执行；未启动或正在停止时忽略，记录由下次定时扫描发送。
     */
    public void wake() {
        ScheduledExecutorService current = executor;
        if (current == null || !waking.compareAndSet(false, true)) return;
        try {
            current.execute(this::relayFromWake);
        } catch (RejectedExecutionException stopping) {
            waking.set(false);
        }
    }

    /**
     * 执行一轮：反复认领直到没有到期记录或出现发送失败；返回本轮发送成功条数。
     */
    public int relayOnce() {
        int total = 0;
        while (true) {
            String token = UUID.randomUUID().toString();
            List<OutboxRow> rows = transactions.execute(
                    status -> outbox.claim(settings.batchSize(), token, LEASE_SECONDS));
            if (rows == null) return total;
            Batch batch = sendBatch(rows, token);
            total += batch.sent();
            if (batch.stopped() || batch.claimed() < settings.batchSize()) return total;
        }
    }

    private Batch sendBatch(List<OutboxRow> rows, String token) {
        int delivered = 0;
        for (OutboxRow row : rows) {
            Exception failure = send(row);
            if (failure == null) {
                transactions.executeWithoutResult(status -> outbox.markSent(row.id(), token));
                sent.increment();
                delivered++;
                continue;
            }
            long delay = Math.min(MAX_DELAY_SECONDS, 1L << Math.min(row.attempts(), 9));
            transactions.executeWithoutResult(status -> {
                outbox.markFailed(row.id(), token, delay, failure.getClass().getName());
                outbox.release(token);
            });
            failed.increment();
            LOG.warn("messaging_outbox_send_failed event_id={} topic={} attempts={} reason={}",
                    row.eventId(), row.topic(), row.attempts() + 1, failure.getClass().getName());
            return new Batch(rows.size(), delivered, true);
        }
        return new Batch(rows.size(), delivered, false);
    }

    private Exception send(OutboxRow row) {
        Span span = traces.start(row.traceParent(), "send " + row.topic(), Span.Kind.PRODUCER);
        try (Tracer.SpanInScope ignored = traces.scope(span)) {
            producer.send(message(row, traces.inject(span)));
            return null;
        } catch (Exception failure) {
            span.error(failure);
            return failure;
        } finally {
            span.end();
        }
    }

    private static org.apache.rocketmq.client.apis.message.Message message(OutboxRow row, String traceParent) {
        MessageBuilder builder = ClientServiceProvider.loadService().newMessageBuilder()
                .setTopic(row.topic())
                .setKeys(row.eventId())
                .setTag(row.eventType())
                .setBody(row.payload().getBytes(StandardCharsets.UTF_8))
                .addProperty(Headers.EVENT_ID, row.eventId())
                .addProperty(Headers.EVENT_TYPE, row.eventType())
                .addProperty(Headers.EVENT_VERSION, Integer.toString(row.version()))
                .addProperty(Headers.EVENT_KEY, row.eventKey());
        if (row.orderKey() != null) builder.setMessageGroup(row.orderKey());
        if (traceParent != null) builder.addProperty(TraceContexts.TRACE_PARENT, traceParent);
        return builder.build();
    }

    private void relayFromWake() {
        waking.set(false);
        safely(this::relayOnce);
    }

    private void purge() {
        safely(() -> {
            if (settings.publishing()) outbox.purgeSent(settings.retentionHours(), 1000);
            if (settings.consuming()) inbox.purge(settings.retentionHours(), 1000);
            return 0;
        });
    }

    private static void safely(java.util.function.IntSupplier action) {
        try {
            action.getAsInt();
        } catch (RuntimeException failure) {
            LOG.error("messaging_relay_failed reason={}", failure.getClass().getName());
        }
    }

    private double pendingOrNaN() {
        try {
            return outbox.pending();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("outbox-relay").factory());
        long interval = settings.interval().toMillis();
        if (settings.publishing())
            executor.scheduleWithFixedDelay(() -> safely(this::relayOnce), 0, interval, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::purge, 1, 60, TimeUnit.MINUTES);
    }

    @Override
    public void stop() {
        ScheduledExecutorService current = executor;
        if (current == null) return;
        executor = null;
        current.shutdown();
        try {
            if (!current.awaitTermination(10, TimeUnit.SECONDS)) current.shutdownNow();
        } catch (InterruptedException interrupted) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    /**
     * 一批的结果。
     */
    private record Batch(int claimed, int sent, boolean stopped) {
    }
}
