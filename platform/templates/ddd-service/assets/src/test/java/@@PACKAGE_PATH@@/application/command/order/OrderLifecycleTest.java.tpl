package @@PACKAGE@@.application.command.order;

import org.jspecify.annotations.Nullable;

import @@PACKAGE@@.application.contract.event.OrderSubmitted;
import @@PACKAGE@@.domain.model.order.Money;
import @@PACKAGE@@.domain.model.order.Order;
import @@PACKAGE@@.domain.model.order.OrderLine;
import @@PACKAGE@@.domain.model.order.OrderRuleViolation;
import @@PACKAGE@@.domain.model.order.OrderStatus;
import @@PACKAGE@@.domain.repository.OrderRepository;
import org.domainforge.shared.BusinessRuleViolation;
import org.domainforge.shared.CurrentScope;
import org.domainforge.shared.CurrentSubject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态迁移用例的快速测试：仓储与事件端口用内存 Fake，不起 Spring 与数据库。拒绝时断言订单没有写入、没有发布事件
 * （真实服务中事件经同事务 Outbox 发布，没有事件即没有 Outbox 记录）。
 */
class OrderLifecycleTest {
    private static final long ACCOUNT = 7;
    private static final long OTHER = 8;

    private final InMemoryOrders orders = new InMemoryOrders();
    private final List<OrderSubmitted> published = new ArrayList<>();
    private CurrentScope scope = new CurrentScope(false, true, Set.of());
    private final OrderLifecycle lifecycle = new OrderLifecycle(
            () -> new CurrentSubject(ACCOUNT, Set.of()), permission -> scope, orders, published::add);

    private Order stored(long owner) {
        Order order = Order.draft(1, owner, null, List.of(new OrderLine(11, "A", 2, new Money(1500))));
        orders.insert(order);
        orders.writes = 0;
        return order;
    }

    @Test
    void submitSavesNextVersionAndPublishesEvent() {
        stored(ACCOUNT);

        OrderChange change = lifecycle.submit(1, 1);

        assertEquals(new OrderChange(1, 2, "SUBMITTED"), change);
        assertEquals(OrderStatus.SUBMITTED, orders.find(1).status());
        assertEquals(1, orders.writes);
        assertEquals(List.of(new OrderSubmitted(1, ACCOUNT, 3000)), published);
    }

    @Test
    void outOfScopeOrderIsReportedAsNotFound() {
        stored(OTHER);

        assertRejected("ORDER_NOT_FOUND", () -> lifecycle.submit(1, 1));
    }

    @Test
    void missingOrderIsReportedAsNotFound() {
        assertRejected("ORDER_NOT_FOUND", () -> lifecycle.pay(1, 1));
    }

    @Test
    void organizationScopeCoversOrdersOfThatOrganization() {
        scope = new CurrentScope(false, false, Set.of(10L));
        orders.insert(Order.draft(1, OTHER, 10L, List.of(new OrderLine(11, "A", 1, new Money(100)))));

        assertEquals("CANCELLED", lifecycle.cancel(1, 1).status());
    }

    @Test
    void staleVersionIsConflict() {
        stored(ACCOUNT);

        assertRejected("VERSION_CONFLICT", () -> lifecycle.submit(1, 2));
    }

    @Test
    void concurrentChangeAfterReadIsConflict() {
        stored(ACCOUNT);
        orders.afterRead = () -> orders.saved.computeIfPresent(1L, (id, order) -> order.cancel());

        assertRejected("VERSION_CONFLICT", () -> lifecycle.submit(1, 1));
    }

    @Test
    void illegalTransitionIsRejectedByAggregate() {
        stored(ACCOUNT);

        String code = assertThrows(OrderRuleViolation.class, () -> lifecycle.pay(1, 1)).code();

        assertEquals("ORDER_STATE_CONFLICT", code);
        assertNothingWritten();
    }

    private void assertRejected(String code, Runnable action) {
        assertEquals(code, assertThrows(BusinessRuleViolation.class, action::run).code());
        assertNothingWritten();
    }

    private void assertNothingWritten() {
        assertEquals(0, orders.writes);
        assertTrue(published.isEmpty());
    }

    /**
     * 按版本条件保存的内存仓储；{@code afterRead} 模拟另一个请求在本次读取之后改了订单。
     */
    private static final class InMemoryOrders implements OrderRepository {
        private final Map<Long, Order> saved = new HashMap<>();
        private int writes;
        private Runnable afterRead = () -> { };

        @Override
        public @Nullable Order lock(long id) {
            return find(id);
        }

        @Override
        public @Nullable Order find(long id) {
            Order order = saved.get(id);
            afterRead.run();
            return order;
        }

        @Override
        public void insert(Order order) {
            saved.put(order.id(), order);
            writes++;
        }

        @Override
        public boolean update(Order order, long expectedVersion) {
            Order current = saved.get(order.id());
            if (current == null || current.version() != expectedVersion) return false;
            saved.put(order.id(), order);
            writes++;
            return true;
        }
    }
}
