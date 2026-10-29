package org.domainforge.gateway.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * API 网关：Nginx 之后、各后端服务之前的路由、登录前置校验、限流与链路标识（traceId）。设计见 docs/architecture/06-API网关.md。
 */
@SpringBootApplication(scanBasePackages = "org.domainforge.gateway")
@EnableScheduling
public class GatewayApplication {
    /**
     * 启动需要 Redis 与 Nacos 的连接配置。
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
