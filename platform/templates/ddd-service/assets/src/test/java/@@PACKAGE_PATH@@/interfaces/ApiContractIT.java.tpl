package @@PACKAGE@@.interfaces;

import @@PACKAGE@@.bootstrap.@@APP_CLASS@@;
import org.domainforge.access.api.SameTokens;
import org.domainforge.buildrules.testsupport.ApiContract;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 本服务的接口契约（H-05）：真实 PostgreSQL 与 Redis 上启动服务，springdoc 生成的 OpenAPI 文档与模块根目录的 openapi.json 一致。
 * 首次在本机运行时写出快照，随服务提交；接口有意变化时按失败信息更新快照，并在 PR 中说明对调用方的影响。
 * 文档端点只在测试中打开，请求带当前 Same-Token。
 */
class ApiContractIT {

    @Test
    void openApiDocumentMatchesSnapshot() throws IOException, InterruptedException {
        assertNotNull(System.getenv("POSTGRES_PORT"), "需要真实数据库验证环境");
        try (ConfigurableApplicationContext application = start(); HttpClient client = HttpClient.newHttpClient()) {
            String port = application.getBean(Environment.class).getProperty("local.server.port");
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                            .header(SameTokens.HEADER, SameTokens.current())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());
            ApiContract.verify(response.body());
        }
    }

    // 只取文档、不处理请求：IAM 与对端地址指向不监听的端口，消息的收发关闭。
    private static ConfigurableApplicationContext start() throws IOException {
        return new SpringApplicationBuilder(@@APP_CLASS@@.class).run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--management.server.port=0",
                "--springdoc.api-docs.enabled=true",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + env("POSTGRES_PORT")
                        + "/domainforge?currentSchema=@@SCHEMA@@",
                "--spring.datasource.username=domainforge",
                "--spring.datasource.password=" + env("POSTGRES_PASSWORD"),
                "--spring.flyway.schemas=@@SCHEMA@@",
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + env("REDIS_PORT"),
                "--spring.data.redis.password=" + env("REDIS_PASSWORD"),
                "--@@ARTIFACT@@.iam.url=http://127.0.0.1:9",
                "--@@ENV@@_PEER_URL=http://127.0.0.1:9",
                "--domainforge.messaging.consumer.enabled=false",
                "--domainforge.messaging.relay.enabled=false",
                "--domainforge.files.local.root=" + Files.createTempDirectory("@@ARTIFACT@@-files-"),
                "--logging.level.root=WARN");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "缺少 " + name);
        return value;
    }
}
