package org.domainforge.iam.client.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** 故障只发生于测试服务器响应，客户端使用产品原生连接池与阶段超时。 */
final class ControlledIdentityEndpoint implements HttpHandler {
  static final String VALID =
      "{\"code\":\"SUCCESS\",\"message\":\"ok\",\"data\":{\"projectId\":\"project-alpha\",\"accountId\":\"00000001-0001-0001-0001-000000000001\",\"permissions\":[\"iam:account:read\"],\"roles\":[]}}";
  final AtomicInteger requests = new AtomicInteger();
  volatile String body = VALID;
  volatile String media = "application/json";
  volatile int status = 200;
  volatile long delay;
  volatile int largeHeader;
  volatile int headerCount;
  volatile String authorization;

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (largeHeader > 0) exchange.getResponseHeaders().set("X-Large", "x".repeat(largeHeader));
    for (int index = 0; index < headerCount; index++)
      exchange.getResponseHeaders().set("X-Count-" + index, "value");
    String value = body;
    long wait = delay;
    exchange.getResponseHeaders().set("Content-Type", media);
    exchange.getResponseHeaders().set("Location", "/should-not-follow");
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try {
      if (wait > 0) Thread.sleep(wait);
      exchange.getResponseBody().write(bytes);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }
}
