import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/** 从真实消费调用采集结果与统计，不把门禁自己的解析当作消费实现。 */
public final class HttpConsumerInventory {
  private static final ConcurrentLinkedQueue<Map<String, Object>> wire =
      new ConcurrentLinkedQueue<>();

  /** 观察器没有业务实例。 */
  private HttpConsumerInventory() {}

  /** 启动隔离 Boot 应用，实际消费并保存原始结果，退出时关闭容器。 */
  public static void main(String[] args) throws Exception {
    var app = new SpringApplication(HttpConsumerFixture.class, Capture.class);
    app.setBannerMode(Banner.Mode.OFF);
    try (var context =
        app.run(
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.main.register-shutdown-hook=false",
            "--logging.level.root=ERROR")) {
      String base =
          "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
      var consumer = new HttpConsumerFixture();
      Map<String, HttpConsumerFixture.Result> results = new LinkedHashMap<>();
      String[] scenarios = {
        "success",
        "empty",
        "rejected",
        "accepted",
        "partial",
        "protocol",
        "unauthenticated",
        "forbidden",
        "unavailable",
        "invalid",
        "unknown",
        "timeout",
        "redirect"
      };
      // 建立连接和编译热路径不参与未知完成的超时场景。
      new HttpConsumerFixture().consume(URI.create(base + "/consume/success"), "json");
      wire.clear();
      for (String scenario : scenarios) {
        results.put(scenario, consumer.consume(URI.create(base + "/consume/" + scenario), "json"));
      }
      for (String protocol : new String[] {"download", "stream", "operations"}) {
        results.put(protocol, consumer.consume(URI.create(base + "/" + protocol), protocol));
      }
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("results", results);
      report.put("outcomes", consumer.outcomes());
      report.put("effects", HttpConsumerFixture.Endpoints.effects.get());
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
      while (wire.size() < results.size() && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      report.put("wire", wire);
      Files.writeString(Path.of(args[0]), JsonMapper.builder().build().writeValueAsString(report));
    }
  }

  /** 门禁采集实际 Servlet 响应全部扇出，不依赖被测客户端报告已丢弃字段。 */
  @Configuration(proxyBeanMethods = false)
  public static class Capture {
    /** 仅记录响应，凭证请求头不进入报告。 */
    @Bean
    public FilterRegistrationBean<jakarta.servlet.Filter> capture() {
      return new FilterRegistrationBean<>(
          (request, response, chain) -> {
            var cached =
                new ContentCachingResponseWrapper(
                    (jakarta.servlet.http.HttpServletResponse) response);
            try {
              chain.doFilter(request, cached);
            } finally {
              Map<String, Object> headers = new LinkedHashMap<>();
              cached.getHeaderNames().forEach(name -> headers.put(name, cached.getHeaders(name)));
              wire.add(
                  Map.of(
                      "path",
                      ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI(),
                      "status",
                      cached.getStatus(),
                      "headers",
                      headers,
                      "body",
                      new String(
                          cached.getContentAsByteArray(),
                          java.nio.charset.StandardCharsets.UTF_8)));
              cached.copyBodyToResponse();
            }
          });
    }
  }
}
