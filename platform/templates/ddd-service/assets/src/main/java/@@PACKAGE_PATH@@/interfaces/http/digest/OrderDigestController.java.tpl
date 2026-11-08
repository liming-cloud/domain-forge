package @@PACKAGE@@.interfaces.http.digest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import @@PACKAGE@@.application.query.digest.OrderDigest;
import @@PACKAGE@@.application.query.digest.OrderDigestQueries;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨服务调用示例入口：本服务只要求登录，读取哪些订单由 @@PEER@@ 按同一用户的权限与范围决定。
 */
@RestController
@PlatformJsonApi
public class OrderDigestController {
    private final OrderDigestQueries digests;

    /**
     * 只依赖应用查询。
     */
    public OrderDigestController(OrderDigestQueries digests) {
        this.digests = digests;
    }

    /**
     * 当前用户在 @@PEER@@ 中的订单摘要；对方 401／403 原样保持，不可用为 503。
     */
    @SaCheckLogin
    @GetMapping("/api/@@NAMESPACE@@/order-digest")
    public OrderDigest digest() {
        return digests.digest();
    }
}
