package @@PACKAGE@@.infrastructure.acl.iam;

import org.domainforge.shared.ApiResponse;
import org.domainforge.shared.CurrentScope;
import org.domainforge.shared.CurrentSubject;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * IAM 当前授权的公开 HTTP 合同；url 为空时经 Nacos 发现名为 iam 的服务。
 *
 * <p>显式传入当前请求经原生解析的 Bearer 令牌，不接收或传递任意账号 ID。Same-Token、链路上下文、超时、不重试与失败分类
 * 由跨服务调用组件统一提供。
 */
@FeignClient(name = "iam", url = "${@@ARTIFACT@@.iam.url:}", contextId = "iamAccess")
public interface IamAccessClient {
    /**
     * 当前主体：账号 ID、角色与精确权限。
     */
    @GetMapping("/internal/access/current")
    ApiResponse<CurrentSubject> current(@RequestHeader("Authorization") String authorization);

    /**
     * 按精确权限读取数据范围。
     */
    @GetMapping("/internal/access/scopes")
    ApiResponse<Map<String, CurrentScope>> scopes(
            @RequestHeader("Authorization") String authorization, @RequestParam("permission") List<String> permissions);
}
