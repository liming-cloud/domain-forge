package org.domainforge.iam.client.internal;

import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/** IAM 专用读取协议；只向此客户端显式传递当前请求令牌。 */
@FeignClient(
    name = "${domainforge.iam-client.service-id:iam}",
    contextId = "domainForgeIamAccess",
    configuration = IamFeignConfiguration.class)
public interface IamAccessFeignClient {
  @GetMapping("/internal/access/current")
  Response current(@RequestHeader("Authorization") String authorization);
}
