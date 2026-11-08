package @@PACKAGE@@.application.query.digest;

import @@PACKAGE@@.application.port.peer.PeerOrder;
import @@PACKAGE@@.application.port.peer.PeerOrders;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 跨服务调用示例：汇总当前用户在 @@PEER@@ 中可见的订单。只读，不跨服务写入。
 */
@Service
public class OrderDigestQueries {

    private final PeerOrders peers;

    /**
     * 经应用端口调用，不依赖 HTTP 客户端类型。
     */
    public OrderDigestQueries(PeerOrders peers) {
        this.peers = peers;
    }

    /**
     * 当前用户的订单摘要；对方的失败原样上抛，不返回部分结果。
     */
    public OrderDigest digest() {
        List<PeerOrder> orders = peers.visible();
        return new OrderDigest(orders.size(), orders.stream().mapToLong(PeerOrder::totalCents).sum());
    }
}
