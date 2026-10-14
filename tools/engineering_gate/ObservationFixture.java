import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 原生 OpenTelemetry 三类信号与有界导出校准，不规定产品观测部署。 */
public final class ObservationFixture implements AutoCloseable {
  private final ObservationTransport transport;
  private final SdkTracerProvider traces;
  private final SdkMeterProvider metrics;
  private final SdkLoggerProvider logs;
  private final Tracer tracer;
  private final Logger logger;
  private final LongCounter results;

  /** 使用标准有界批处理器；导出期限独立于业务处理。 */
  public ObservationFixture(URI collector) {
    transport = new ObservationTransport(collector);
    var resource =
        Resource.create(
            Attributes.builder()
                .put("service.name", "gate-observation")
                .put("service.version", "1.0.0")
                .put("project", "alpha")
                .put("environment", "calibration")
                .build());
    traces =
        SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(
                BatchSpanProcessor.builder(transport.traces())
                    .setMaxQueueSize(8)
                    .setMaxExportBatchSize(2)
                    .setScheduleDelay(Duration.ofMillis(100))
                    .setExporterTimeout(Duration.ofMillis(250))
                    .build())
            .build();
    metrics =
        SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(
                PeriodicMetricReader.builder(transport.metrics())
                    .setInterval(Duration.ofMillis(100))
                    .build())
            .build();
    logs =
        SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(
                BatchLogRecordProcessor.builder(transport.logs())
                    .setMaxQueueSize(8)
                    .setMaxExportBatchSize(2)
                    .setScheduleDelay(Duration.ofMillis(100))
                    .setExporterTimeout(Duration.ofMillis(250))
                    .build())
            .build();
    tracer = traces.get("gate");
    logger = logs.get("gate");
    results = metrics.get("gate").counterBuilder("operation.results").build();
  }

  /** 业务结果独立于普通遥测；外部输入不成为身份、关联或聚合维度。 */
  public String operation(String outcome, String untrusted) {
    String actual =
        Set.of("completed", "rejected", "unavailable").contains(outcome) ? outcome : "unknown";
    Span parent = tracer.spanBuilder("operation").setAttribute("outcome", actual).startSpan();
    try (Scope scope = parent.makeCurrent()) {
      Span child = tracer.spanBuilder("dependency").startSpan();
      child.end();
      logger
          .logRecordBuilder()
          .setBody("operation-finished")
          .setAttribute(AttributeKey.stringKey("outcome"), actual)
          .emit();
      results.add(1, Attributes.of(AttributeKey.stringKey("outcome"), actual));
      return actual;
    } finally {
      parent.end();
    }
  }

  /** 本地自诊断不依赖已故障的外部接收链路。 */
  public java.util.Map<String, Object> health() {
    return java.util.Map.of(
        "businessReady",
        true,
        "observationAvailable",
        transport.available(),
        "failedExports",
        transport.failedExports());
  }

  /** 仅供场景同步观察，业务请求不等待导出完成。 */
  public void flush() {
    CompletableResultCode.ofAll(
            java.util.List.of(traces.forceFlush(), metrics.forceFlush(), logs.forceFlush()))
        .join(2, TimeUnit.SECONDS);
  }

  /** 关闭本轮 SDK 工作线程。 */
  @Override
  public void close() {
    traces.close();
    metrics.close();
    logs.close();
  }
}
