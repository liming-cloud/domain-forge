package example.consumer.a;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立消费验证入口，仅运行合成合同场景，不提供业务能力。 */
@OpenAPIDefinition(
    servers = @io.swagger.v3.oas.annotations.servers.Server(url = "/"),
    info = @Info(title = "独立消费者 A 合同", version = "0.1.0-r2.1"))
@SpringBootApplication
public class ConsumerApplication {
  /**
   * 启动自有应用，配置和生命周期由该消费者管理。
   *
   * @param args 宿主启动参数
   */
  public static void main(String[] args) {
    SpringApplication.run(ConsumerApplication.class, args);
  }
}
