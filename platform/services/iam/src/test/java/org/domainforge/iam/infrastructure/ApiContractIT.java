package org.domainforge.iam.infrastructure;

import org.domainforge.access.api.SameTokens;
import org.domainforge.buildrules.testsupport.ApiContract;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IAM 的接口契约（H-05）：springdoc 生成的 OpenAPI 文档与模块根目录的 openapi.json 一致；接口有意变化时按失败信息更新快照。
 * 文档端点按框架处理器免于访问声明检查，但仍要求有效 Same-Token。
 */
class ApiContractIT {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void openApiDocumentMatchesSnapshot() throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(docs().header(SameTokens.HEADER, SameTokens.current()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        ApiContract.verify(response.body());
    }

    @Test
    void documentRequiresSameToken() throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(docs().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(), response.body());
    }

    private static HttpRequest.Builder docs() {
        String port = SharedIamApplication.get().getBean(Environment.class).getProperty("local.server.port");
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"));
    }
}
