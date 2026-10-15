package org.domainforge.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class ApplicationHealthTest {

  // 使用实际 HTTP 观察最小装配，不以上下文存在代替健康响应。
  @Test
  void healthIsUpWithoutExternalServicesAndManagementDetails() throws Exception {
    try (var context = SpringApplication.run(DomainForgeApplication.class, "--server.port=0");
        var client = HttpClient.newHttpClient()) {
      String port = context.getEnvironment().getProperty("local.server.port");
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
              .timeout(Duration.ofSeconds(5))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("{\"status\":\"UP\"}", response.body());
      var hidden =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/env"))
              .timeout(Duration.ofSeconds(5))
              .build();
      assertEquals(404, client.send(hidden, HttpResponse.BodyHandlers.discarding()).statusCode());
    }
  }
}
