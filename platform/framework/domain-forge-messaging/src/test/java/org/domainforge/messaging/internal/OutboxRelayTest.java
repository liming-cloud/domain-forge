package org.domainforge.messaging.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.domainforge.messaging.api.OutgoingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中继生命周期：停止过程中和停止之后，业务线程提交后的唤醒不抛异常。
 */
class OutboxRelayTest {
    @Test
    void wakeDuringAndAfterStopDoesNotThrow() throws Exception {
        CountDownLatch claiming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var relay = new OutboxRelay(new BlockingOutbox(claiming, release), null,
                TransactionOperations.withoutTransaction(), null,
                new OutboxRelay.Settings(Duration.ofHours(1), 10, 24, true, false), new SimpleMeterRegistry(),
                new TraceContexts(Tracer.NOOP, Propagator.NOOP));
        relay.start();
        assertTrue(claiming.await(2, TimeUnit.SECONDS));
        Thread stopping = Thread.ofPlatform().start(relay::stop);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (stopping.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(stopping.getState() == Thread.State.TIMED_WAITING, "停止线程在等待本轮发送结束");
        assertDoesNotThrow(relay::wake);
        release.countDown();
        stopping.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse(relay.isRunning());
        assertDoesNotThrow(relay::wake);
    }

    /**
     * 第一次抢占时阻塞，模拟停止时正在进行的一轮发送。
     */
    private record BlockingOutbox(CountDownLatch claiming, CountDownLatch release) implements OutboxMapper {
        @Override
        public List<OutboxRow> claim(int limit, String token, long leaseSeconds) {
            claiming.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }

        @Override
        public int insert(OutgoingEvent event, String eventId, String traceParent) {
            return 0;
        }

        @Override
        public int markSent(long id, String token) {
            return 0;
        }

        @Override
        public int markFailed(long id, String token, long delaySeconds, String error) {
            return 0;
        }

        @Override
        public int release(String token) {
            return 0;
        }

        @Override
        public int purgeSent(long retentionHours, int limit) {
            return 0;
        }

        @Override
        public long pending() {
            return 0;
        }
    }
}
