import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/** 将原生 SDK 数据送到真实回环接收端；JSON 仅为校准传输，不声称 OTLP。 */
public final class ObservationTransport {
  private final URI collector;
  private final HttpClient client = HttpClient.newHttpClient();
  private final Map<String, Boolean> health = new ConcurrentHashMap<>();
  private final AtomicInteger failures = new AtomicInteger();

  /** 接收端由本轮观察器创建，不接受远程生产地址。 */
  public ObservationTransport(URI collector) {
    if (!collector.getHost().equals("127.0.0.1") || !collector.getScheme().equals("http")) {
      throw new IllegalArgumentException("loopback-only");
    }
    this.collector = collector;
  }

  /** 三类信号都已成功送达才报告观测可用。 */
  public boolean available() {
    return health.size() == 3 && health.values().stream().allMatch(Boolean::booleanValue);
  }

  /** 累计已观察的导出失败，不能据此声称其他数据零丢失。 */
  public int failedExports() {
    return failures.get();
  }

  /** 同步发送只发生在原生 SDK 的后台工作线程，等待有界。 */
  private CompletableResultCode send(String signal, Object values) {
    boolean success = false;
    try {
      String body = JsonMapper.builder().build().writeValueAsString(values);
      var request =
          HttpRequest.newBuilder(collector.resolve("/" + signal))
              .timeout(Duration.ofMillis(200))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      success = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (Exception error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    health.put(signal, success);
    if (!success) {
      failures.incrementAndGet();
    }
    return success ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
  }

  /** 固定字符串键保留原生属性，不从接收结果重造业务事实。 */
  private static Map<String, Object> attributes(io.opentelemetry.api.common.Attributes values) {
    Map<String, Object> result = new LinkedHashMap<>();
    values.forEach((key, value) -> result.put(key.getKey(), value));
    return result;
  }

  /** 使用原生 SpanData 的实际 trace、span 和父关联。 */
  public SpanExporter traces() {
    return new SpanExporter() {
      /** 发送 SDK 真实完成的 span。 */
      @Override
      public CompletableResultCode export(Collection<SpanData> spans) {
        return send(
            "traces",
            spans.stream()
                .map(
                    span ->
                        Map.of(
                            "name",
                            span.getName(),
                            "trace",
                            span.getTraceId(),
                            "span",
                            span.getSpanId(),
                            "parent",
                            span.getParentSpanId(),
                            "attributes",
                            attributes(span.getAttributes()),
                            "resource",
                            attributes(span.getResource().getAttributes())))
                .toList());
      }

      /** 此适配器没有额外队列。 */
      @Override
      public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
      }

      /** 生命周期由 SDK 管理。 */
      @Override
      public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
      }
    };
  }

  /** 记录真实 SDK 聚合点，业务拒绝独立于完成数量。 */
  public MetricExporter metrics() {
    return new MetricExporter() {
      /** 使用累积计数，不在导出时重算期望值。 */
      @Override
      public AggregationTemporality getAggregationTemporality(InstrumentType type) {
        return AggregationTemporality.CUMULATIVE;
      }

      /** 发送原生计数点及其有限维度。 */
      @Override
      public CompletableResultCode export(Collection<MetricData> metrics) {
        return send(
            "metrics",
            metrics.stream()
                .flatMap(
                    metric ->
                        metric.getLongSumData().getPoints().stream()
                            .map(
                                point ->
                                    Map.of(
                                        "name",
                                        metric.getName(),
                                        "value",
                                        point.getValue(),
                                        "attributes",
                                        attributes(point.getAttributes()),
                                        "resource",
                                        attributes(metric.getResource().getAttributes()))))
                .toList());
      }

      /** 没有适配器额外缓冲。 */
      @Override
      public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
      }

      /** 停止动作由 SDK 负责。 */
      @Override
      public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
      }
    };
  }

  /** 结构化日志保留原生当前上下文关联，不复制请求体或凭证。 */
  public LogRecordExporter logs() {
    return new LogRecordExporter() {
      /** 发送实际日志与最小诊断属性。 */
      @Override
      public CompletableResultCode export(Collection<LogRecordData> logs) {
        return send(
            "logs",
            logs.stream()
                .map(
                    log ->
                        Map.of(
                            "body",
                            log.getBodyValue().asString(),
                            "trace",
                            log.getSpanContext().getTraceId(),
                            "span",
                            log.getSpanContext().getSpanId(),
                            "attributes",
                            attributes(log.getAttributes()),
                            "resource",
                            attributes(log.getResource().getAttributes())))
                .toList());
      }

      /** 此适配器不自行缓存日志。 */
      @Override
      public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
      }

      /** 停止动作由原生 SDK 负责。 */
      @Override
      public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
      }
    };
  }
}
