package org.domainforge.iam.infrastructure;

import org.domainforge.buildrules.testsupport.ApiContract;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * IAM 的接口契约（H-05）：springdoc 生成的 OpenAPI 文档与模块根目录的 openapi.json 一致；接口有意变化时按失败信息更新快照。
 * <p>
 * 直接调用 springdoc 的文档 Bean，不经 HTTP：文档端点不属于业务接口，访问控制按未声明访问策略拒绝它。
 */
class ApiContractIT {

    @Test
    void openApiDocumentMatchesSnapshot() throws Exception {
        byte[] document = SharedIamApplication.get().getBean(OpenApiWebMvcResource.class)
                .openapiJson(new MockHttpServletRequest(), "/v3/api-docs", Locale.ROOT);

        ApiContract.verify(new String(document, StandardCharsets.UTF_8));
    }
}
