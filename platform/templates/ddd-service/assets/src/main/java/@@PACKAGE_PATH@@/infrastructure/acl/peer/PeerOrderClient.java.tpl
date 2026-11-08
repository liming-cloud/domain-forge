package @@PACKAGE@@.infrastructure.acl.peer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * @@PEER@@ 的订单公开接口；url 为空时经 Nacos 发现名为 @@PEER@@ 的服务。
 *
 * <p>用户令牌、Same-Token 与链路上下文由跨服务调用组件自动附加，这里不声明。
 */
@FeignClient(name = "@@PEER@@", url = "${@@ENV@@_PEER_URL:}", contextId = "peerOrders")
public interface PeerOrderClient {
    /**
     * 当前用户可见的订单。
     */
    @GetMapping("/api/orders")
    List<Order> visible();

    /**
     * 对方订单表示中本服务用到的字段，其余字段忽略。
     *
     * @param id         订单 ID
     * @param status     订单状态
     * @param totalCents 订单总额（分）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Order(String id, String status, long totalCents) {
    }
}
