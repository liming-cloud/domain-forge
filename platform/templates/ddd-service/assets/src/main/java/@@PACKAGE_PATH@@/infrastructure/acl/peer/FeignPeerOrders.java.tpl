package @@PACKAGE@@.infrastructure.acl.peer;

import @@PACKAGE@@.application.port.peer.PeerOrder;
import @@PACKAGE@@.application.port.peer.PeerOrders;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.AccessUnavailableException;
import org.domainforge.servicecall.api.ServiceCallException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 经 Feign 读取 @@PEER@@ 的订单，并把调用失败翻译为本服务的访问失败。
 */
@Component
public class FeignPeerOrders implements PeerOrders {
    private final PeerOrderClient client;

    /**
     * 只依赖本服务声明的客户端。
     */
    public FeignPeerOrders(PeerOrderClient client) {
        this.client = client;
    }

    @Override
    public List<PeerOrder> visible() {
        try {
            List<PeerOrderClient.Order> orders = client.visible();
            if (orders == null) throw new AccessUnavailableException();
            return orders.stream().map(order -> new PeerOrder(order.id(), order.status(), order.totalCents())).toList();
        } catch (ServiceCallException failure) {
            throw translate(failure);
        }
    }

    /**
     * 对方的 401、403 保持语义；不可用、结果未知、拒绝一律为依赖不可用，不当作空列表。
     */
    private static RuntimeException translate(ServiceCallException failure) {
        return switch (failure.failure()) {
            case UNAUTHENTICATED -> new AccessException(AccessFailure.UNAUTHENTICATED);
            case FORBIDDEN -> new AccessException(AccessFailure.FORBIDDEN);
            default -> new AccessUnavailableException();
        };
    }
}
