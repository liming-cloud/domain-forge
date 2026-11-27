package @@PACKAGE@@.interfaces;

import @@PACKAGE@@.bootstrap.@@APP_CLASS@@;
import org.domainforge.buildrules.testsupport.ApiContract;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 本服务的接口契约（H-05）：真实 PostgreSQL 与 Redis 上启动服务，springdoc 生成的 OpenAPI 文档与模块根目录的 openapi.json 一致。
 * 首次在本机运行时写出快照，随服务提交；接口有意变化时按失败信息更新快照，并在 PR 中说明对调用方的影响。
 * <p>
 * 直接调用 springdoc 的文档 Bean，不经 HTTP：文档端点不属于业务接口，访问控制按未声明访问策略拒绝它。
 */
class ApiContractIT {

    @Test
    void openApiDocumentMatchesSnapshot() throws Exception {
        assertNotNull(System.getenv("POSTGRES_PORT"), "需要真实数据库验证环境");
        try (ConfigurableApplicationContext application = start()) {
            byte[] document = application.getBean(OpenApiWebMvcResource.class)
                    .openapiJson(new MockHttpServletRequest(), "/v3/api-docs", Locale.ROOT);

            ApiContract.verify(new String(document, StandardCharsets.UTF_8));
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
