package @@PACKAGE@@.domain.model.order;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单状态机与跨行不变式：每个合法迁移、每个非法迁移与每条约束都有用例。
 */
class OrderTest {
    private static OrderLine line(long id, String sku, int quantity, long cents) {
        return new OrderLine(id, sku, quantity, new Money(cents));
    }

    private static Order draft() {
        return Order.draft(1, 7, 10L, List.of(line(1, "A", 2, 1500)));
    }

    private static String codeOf(Runnable action) {
        return assertThrows(OrderRuleViolation.class, action::run).code();
    }

    @Test
    void totalIsSumOfLineSubtotals() {
        Order order = draft().addLine(line(2, "B", 1, 500));
        assertEquals(3500, order.total().cents());
        assertEquals(2, order.version());
    }

    @Test
    void happyPathDraftSubmittedPaid() {
        Order submitted = draft().submit();
        assertEquals(OrderStatus.SUBMITTED, submitted.status());
        var event = submitted.submitted().orElseThrow();
        assertEquals(1, event.orderId());
        assertEquals(7, event.ownerId());
        assertEquals(3000, event.totalCents());
        Order paid = submitted.pay();
        assertEquals(OrderStatus.PAID, paid.status());
        assertTrue(paid.submitted().isEmpty(), "事件只随提交产生");
        assertEquals(3, paid.version());
    }

    @Test
    void cancelFromDraftAndSubmittedOnly() {
        assertEquals(OrderStatus.CANCELLED, draft().cancel().status());
        assertEquals(OrderStatus.CANCELLED, draft().submit().cancel().status());
        assertEquals("ORDER_STATE_CONFLICT", codeOf(() -> draft().submit().pay().cancel()));
        assertEquals("ORDER_STATE_CONFLICT", codeOf(() -> draft().cancel().cancel()));
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertEquals("ORDER_STATE_CONFLICT", codeOf(() -> draft().pay()));
        assertEquals("ORDER_STATE_CONFLICT", codeOf(() -> draft().submit().submit()));
        assertEquals("ORDER_STATE_CONFLICT", codeOf(() -> draft().submit().addLine(line(2, "B", 1, 1))));
        assertEquals("ORDER_STATE_CONFLICT", codeOf(() -> draft().cancel().submit()));
        assertEquals("ORDER_STATE_CONFLICT", codeOf(() -> draft().submit().pay().pay()));
    }

    @Test
    void emptyOrderCannotBeSubmitted() {
        assertEquals("ORDER_EMPTY", codeOf(() -> Order.draft(1, 7, null, List.of()).submit()));
    }

    @Test
    void linesAreUniqueAndLimited() {
        assertEquals("ORDER_LINE_DUPLICATE", codeOf(() -> draft().addLine(line(2, "A", 1, 1))));
        List<OrderLine> full = IntStream.rangeClosed(1, Order.MAX_LINES).mapToObj(i -> line(i, "S" + i, 1, 1)).toList();
        Order order = Order.draft(1, 7, null, full);
        assertEquals("ORDER_LINES_FULL", codeOf(() -> order.addLine(line(99, "X", 1, 1))));
    }

    @Test
    void totalIsCappedAcrossLines() {
        Order order = Order.draft(1, 7, null, List.of(line(1, "A", 1, 60_000_000)));
        assertEquals("ORDER_TOTAL_EXCEEDED", codeOf(() -> order.addLine(line(2, "B", 1, 60_000_000))));
        assertEquals(120_000_000, line(2, "B", 2, 60_000_000).subtotal().cents(), "单行小计本身有效，订单总额由聚合约束");
    }

    @Test
    void valuesAreValidated() {
        assertEquals("ORDER_INVALID", codeOf(() -> line(1, " ", 1, 1)));
        assertEquals("ORDER_INVALID", codeOf(() -> line(1, "A", 0, 1)));
        assertEquals("ORDER_INVALID", codeOf(() -> line(1, "A", OrderLine.MAX_QUANTITY + 1, 1)));
        assertEquals("ORDER_INVALID", codeOf(() -> line(1, "A", 1, 0)));
        assertEquals("ORDER_INVALID", codeOf(() -> new Money(-1)));
        assertEquals("ORDER_INVALID", codeOf(() -> Order.draft(0, 7, null, List.of())));
        assertEquals(new Money(100), new Money(60).plus(new Money(40)));
    }
}
