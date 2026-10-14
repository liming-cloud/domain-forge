import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.json.JsonMapper;

/** 真实接收 SDK 导出数据并制造接收端故障，不从连接成功推定有效观测。 */
public final class ObservationInventory {
  /** 工具没有业务实例。 */
  private ObservationInventory() {}

  /** 执行正常、故障持续及恢复，保留接收端实际内容供独立规则核对。 */
  public static void main(String[] args) throws Exception {
    var mapper = JsonMapper.builder().build();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newFixedThreadPool(4);
    server.setExecutor(executor);
    var failed = new AtomicBoolean();
    List<Object> wire = new CopyOnWriteArrayList<>();
    Map<String, List<Object>> received = new LinkedHashMap<>();
    for (String signal : List.of("traces", "metrics", "logs")) {
      received.put(signal, new CopyOnWriteArrayList<>());
      server.createContext(
          "/" + signal,
          exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            wire.add(
                Map.of(
                    "signal",
                    signal,
                    "headers",
                    Map.copyOf(exchange.getRequestHeaders()),
                    "body",
                    new String(body, java.nio.charset.StandardCharsets.UTF_8)));
            if (failed.get()) {
              try {
                Thread.sleep(400);
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              }
              exchange.sendResponseHeaders(503, -1);
            } else {
              received.get(signal).add(mapper.readValue(body, List.class));
              exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
          });
    }
    server.start();
    Map<String, Object> report = new LinkedHashMap<>();
    try (var fixture =
        new ObservationFixture(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))) {
      observe(fixture, failed, received, wire, report);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
    Files.writeString(Path.of(args[0]), mapper.writeValueAsString(report));
  }

  /** 分阶段调用实际业务，接收端后台事实单独固定。 */
  private static void observe(
      ObservationFixture fixture,
      AtomicBoolean failed,
      Map<String, List<Object>> received,
      List<Object> wire,
      Map<String, Object> report)
      throws Exception {
    List<String> normal = new ArrayList<>();
    for (String outcome : List.of("completed", "rejected", "unavailable")) {
      normal.add(fixture.operation(outcome, "Bearer observation-calibration-secret\r\nforged"));
      fixture.flush();
    }
    awaitMetrics(fixture, received, Map.of("completed", 1L, "rejected", 1L, "unavailable", 1L));
    report.put("normalResults", normal);
    report.put("normalSignals", snapshot(received));
    report.put("normalHealth", fixture.health());
    failed.set(true);
    long start = System.nanoTime();
    List<String> during = new ArrayList<>();
    for (int request = 0; request < 100; request++) {
      during.add(fixture.operation("completed", "untrusted-" + request));
    }
    report.put("businessMillis", (System.nanoTime() - start) / 1_000_000);
    fixture.flush();
    report.put("failureResults", during);
    report.put("failureHealth", fixture.health());
    Thread.sleep(600);
    failed.set(false);
    report.put("beforeRecovery", snapshot(received));
    recovery(fixture, report);
    fixture.flush();
    awaitMetrics(fixture, received, Map.of("completed", 102L, "rejected", 1L, "unavailable", 1L));
    report.put("recoveryHealth", fixture.health());
    report.put("allSignals", snapshot(received));
    report.put("wire", List.copyOf(wire));
  }

  /** 将恢复操作绑定观察器产生的新上下文，排除旧日志重放。 */
  private static void recovery(ObservationFixture fixture, Map<String, Object> report) {
    String trace = java.util.UUID.randomUUID().toString().replace("-", "");
    report.put("recoveryTrace", trace);
    var parent =
        io.opentelemetry.api.trace.SpanContext.create(
            trace,
            "1234567890abcdef",
            io.opentelemetry.api.trace.TraceFlags.getSampled(),
            io.opentelemetry.api.trace.TraceState.getDefault());
    try (var scope = io.opentelemetry.api.trace.Span.wrap(parent).makeCurrent()) {
      report.put("recoveryResult", fixture.operation("completed", "ignored"));
    }
  }

  /** 原生周期采样可能已在执行；等待接收事实收敛，超期保留原报告让规则拒绝。 */
  private static void awaitMetrics(
      ObservationFixture fixture, Map<String, List<Object>> received, Map<String, Long> expected)
      throws InterruptedException {
    for (int attempt = 0; attempt < 30 && !metricCounts(received).equals(expected); attempt++) {
      Thread.sleep(50);
      fixture.flush();
    }
  }

  /** 累计计数按原生采样值取最大值，不把异步到达顺序当作采样顺序。 */
  private static Map<String, Long> metricCounts(Map<String, List<Object>> received) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Object batch : received.get("metrics")) {
      for (Object value : (List<?>) batch) {
        Map<?, ?> row = (Map<?, ?>) value;
        String outcome = (String) ((Map<?, ?>) row.get("attributes")).get("outcome");
        counts.merge(outcome, ((Number) row.get("value")).longValue(), Math::max);
      }
    }
    return counts;
  }

  /** 固定阶段接收事实，后台后续到达不能回写旧阶段证据。 */
  private static Map<String, Object> snapshot(Map<String, List<Object>> received) {
    Map<String, Object> result = new LinkedHashMap<>();
    received.forEach((name, records) -> result.put(name, List.copyOf(records)));
    return result;
  }
}
