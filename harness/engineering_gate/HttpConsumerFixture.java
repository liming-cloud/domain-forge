import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** 消费合同的隔离校准对象；不是平台客户端或业务服务。 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(HttpConsumerFixture.Endpoints.class)
public class HttpConsumerFixture {
  private final HttpClient client = HttpClient.newHttpClient();
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final Map<String, Integer> outcomes = new ConcurrentHashMap<>();

  /** 消费端可观察的结果保留完成含义，不从状态码推断重试。 */
  public record Result(String outcome, String code, Object data, int status) {}

  /** 只做一次真实请求，超时、畸形或未知合同保持结果未知。 */
  public Result consume(URI uri, String protocol) {
    Result result;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofMillis(500))
              .header("Authorization", "Bearer consumer-calibration-secret")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      try {
        result = interpret(response.statusCode(), response.body(), protocol);
      } catch (tools.jackson.core.JacksonException error) {
        result = new Result("unknown", "INVALID_RESPONSE", null, response.statusCode());
      }
    } catch (Exception error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      result = new Result("unknown", "NO_VALID_RESPONSE", null, 0);
    }
    outcomes.merge(result.outcome(), 1, Integer::sum);
    return result;
  }

  /** 有限业务类别独立于 HTTP 成功率；动态地址和凭证不进入维度。 */
  public Map<String, Integer> outcomes() {
    return Map.copyOf(outcomes);
  }

  /** 按已声明的隔离合同解释响应，保留协议失败及未完成状态。 */
  private Result interpret(int status, String body, String protocol) {
    if (status < 200 || status >= 300 && status < 400) {
      return new Result("unknown", "UNEXPECTED_HTTP_STATUS", null, status);
    }
    if (status >= 400) {
      String outcome =
          switch (status) {
            case 401 -> "unauthenticated";
            case 403 -> "forbidden";
            case 400 -> "protocol-error";
            default -> "unavailable";
          };
      return new Result(outcome, "HTTP_FAILURE", null, status);
    }
    if (!protocol.equals("json")) {
      return new Result("completed", "PROTOCOL_RESULT", body, status);
    }
    return interpretJson(status, body);
  }

  /** 只解释已确认普通 JSON 外壳的有限结果类别。 */
  private Result interpretJson(int status, String body) {
    var value = mapper.readTree(body);
    if (!value.isObject()
        || value.size() != 3
        || !value.has("data")
        || !value.path("code").isString()
        || !value.path("message").isString()) {
      return new Result("unknown", "INVALID_RESPONSE", null, status);
    }
    String code = value.path("code").asString();
    String outcome =
        switch (code) {
          case "SUCCESS" -> "completed";
          case "LIMIT_REACHED" -> "rejected";
          case "ACCEPTED" -> "pending";
          case "PARTIAL" -> "partial";
          default -> "unknown";
        };
    return new Result(outcome, code, value.get("data"), status);
  }

  /** 真实 Servlet 入口覆盖消费结果与非普通 JSON 协议。 */
  @RestController
  public static class Endpoints {
    static final AtomicInteger effects = new AtomicInteger();

    /** 普通 JSON 保持三字段；未知响应专用于消费失败校准。 */
    @GetMapping(value = "/consume/{kind}", produces = "application/json")
    public ResponseEntity<String> result(@PathVariable String kind) throws InterruptedException {
      if (kind.equals("timeout")) {
        effects.incrementAndGet();
        Thread.sleep(900);
      }
      int status = status(kind);
      String code = code(kind);
      String data =
          kind.equals("partial") || kind.equals("accepted")
              ? "{\"statusUrl\":\"/operations/42\"}"
              : "null";
      String body =
          kind.equals("invalid")
              ? "{invalid"
              : "{\"code\":\"" + code + "\",\"message\":\"校准\",\"data\":" + data + "}";
      return ResponseEntity.status(status).body(body);
    }

    /** 隔离端点按场景保持真实 HTTP 分类。 */
    private int status(String kind) {
      return switch (kind) {
        case "redirect" -> 302;
        case "protocol" -> 400;
        case "unauthenticated" -> 401;
        case "forbidden" -> 403;
        case "unavailable" -> 503;
        default -> 200;
      };
    }

    /** 只映射当前已声明的合成业务码。 */
    private String code(String kind) {
      return switch (kind) {
        case "rejected" -> "LIMIT_REACHED";
        case "accepted" -> "ACCEPTED";
        case "partial" -> "PARTIAL";
        case "unknown" -> "UNRECOGNIZED";
        default -> "SUCCESS";
      };
    }

    /** 下载维持字节协议。 */
    @GetMapping(value = "/download", produces = "application/octet-stream")
    public byte[] download() {
      return new byte[] {65, 66};
    }

    /** 流协议维持事件边界。 */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public String stream() {
      return "data: first\n\ndata: last\n\n";
    }

    /** 运维端点独立于普通 JSON 外壳。 */
    @GetMapping(value = "/operations", produces = "application/json")
    public Map<String, String> operations() {
      return Map.of("status", "UP");
    }
  }
}
