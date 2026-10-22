package @@PACKAGE@@.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import @@PACKAGE@@.application.command.WritePair;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** 用受控出口施加阻塞与 I/O 故障，观察真实队列、事务和恢复日志。 */
class LoggingFailureTest {
  /** 满队列和出口失败不得改写已提交事务，恢复须产生新的可读日志。 */
  @Test
  void boundedLoggingCannotUndoCommittedWork() throws Exception {
    try (var context =
        new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run()) {
      var loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
      var root = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      var original = (AsyncAppender) root.getAppender("ASYNC");
      assertEquals(256, original.getQueueSize());
      assertTrue(original.isNeverBlock());
      var blocked = new BlockedOutput();
      var sink = sink(loggerContext, blocked);
      var asynchronous = asynchronous(loggerContext, original, sink);
      root.detachAppender(original);
      root.addAppender(asynchronous);
      try {
        blockedWrite(context, root, asynchronous, blocked);
        failedAndRecoveredWrite(context, root, asynchronous, sink);
      } finally {
        blocked.release.countDown();
        root.detachAppender(asynchronous);
        asynchronous.stop();
        root.addAppender(original);
      }
    }
  }

  /** 设置与模板相同的原生有界队列，仅替换验证出口。 */
  private AsyncAppender asynchronous(
      LoggerContext context, AsyncAppender original, OutputStreamAppender<ILoggingEvent> sink) {
    var result = new AsyncAppender();
    result.setContext(context);
    result.setName("ASYNC");
    result.setQueueSize(original.getQueueSize());
    result.setNeverBlock(original.isNeverBlock());
    result.setDiscardingThreshold(original.getDiscardingThreshold());
    result.setMaxFlushTime(original.getMaxFlushTime());
    result.addAppender(sink);
    result.start();
    return result;
  }

  /** 使用原生输出流适配，故障由真实 I/O 异常触发。 */
  private OutputStreamAppender<ILoggingEvent> sink(LoggerContext context, OutputStream stream) {
    var result = new OutputStreamAppender<ILoggingEvent>();
    var encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern("%msg%n");
    encoder.start();
    result.setContext(context);
    result.setEncoder(encoder);
    result.setOutputStream(stream);
    result.start();
    return result;
  }

  /** 验证队列满后的业务完成时限及真实数据库提交。 */
  private void blockedWrite(
      org.springframework.context.ConfigurableApplicationContext context,
      ch.qos.logback.classic.Logger root,
      AsyncAppender asynchronous,
      BlockedOutput blocked)
      throws Exception {
    var jdbc = new DatabaseFixture(context);
    jdbc.clear();
    root.info("block-entry");
    assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          for (int i = 0; i < 1000; i++) {
            root.info("bounded-event");
          }
          context.getBean(WritePair.class).execute(2000, "none");
        });
    assertEquals(0, asynchronous.getRemainingCapacity());
    assertEquals("DEGRADED", context.getBean(LoggingState.class).health().getStatus().getCode());
    assertEquals(2, jdbc.count());
    blocked.release.countDown();
    awaitDrained(asynchronous);
  }

  /** 验证 I/O 失败可见且恢复产生新日志，不能篡改之前的事务。 */
  private void failedAndRecoveredWrite(
      org.springframework.context.ConfigurableApplicationContext context,
      ch.qos.logback.classic.Logger root,
      AsyncAppender asynchronous,
      OutputStreamAppender<ILoggingEvent> sink)
      throws Exception {
    var jdbc = new DatabaseFixture(context);
    sink.setOutputStream(new BrokenOutput());
    root.info("fail-entry");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (sink.isStarted() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(sink.isStarted());
    assertEquals("DEGRADED", context.getBean(LoggingState.class).health().getStatus().getCode());
    assertTimeoutPreemptively(
        Duration.ofSeconds(3), () -> context.getBean(WritePair.class).execute(3000, "none"));
    assertEquals(4, jdbc.count());
    var recovered = new ByteArrayOutputStream();
    sink.setOutputStream(recovered);
    sink.start();
    root.info("fresh-recovered-event");
    awaitDrained(asynchronous);
    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!recovered.toString().contains("fresh-recovered-event")
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(recovered.toString().contains("fresh-recovered-event"));
    assertEquals("UP", context.getBean(LoggingState.class).health().getStatus().getCode());
  }

  /** 等待当前有限队列排空，不把历史丢失解释为送达。 */
  private void awaitDrained(AsyncAppender appender) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (appender.getNumberOfElementsInQueue() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(0, appender.getNumberOfElementsInQueue());
  }

  /** 首次写入受控阻塞；测试清理必定释放。 */
  private static class BlockedOutput extends OutputStream {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void write(int value) throws IOException {
      entered.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new IOException("验证出口阻塞超时");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IOException(failure);
      }
    }
  }

  /** 原生 OutputStreamAppender 会停用失败出口，状态必须可见。 */
  private static class BrokenOutput extends OutputStream {
    @Override
    public void write(int value) throws IOException {
      throw new IOException("验证出口失败");
    }
  }
}
