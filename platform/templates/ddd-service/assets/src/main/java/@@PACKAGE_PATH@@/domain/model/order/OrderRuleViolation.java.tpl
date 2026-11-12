package @@PACKAGE@@.domain.model.order;

import org.domainforge.shared.BusinessRuleViolation;

import java.io.Serial;

/**
 * 订单聚合的规则违反：错误码稳定，提示是固定说明，可以对外返回。
 */
public final class OrderRuleViolation extends BusinessRuleViolation {
    @Serial
    private static final long serialVersionUID = 1L;

    private OrderRuleViolation(Kind kind, String code, String message) {
        super(kind, code, message);
    }

    /**
     * 字段取值违反规则（400）。
     */
    public static OrderRuleViolation invalid(String message) {
        return new OrderRuleViolation(Kind.INVALID_REQUEST, "ORDER_INVALID", message);
    }

    /**
     * 当前状态不允许该操作（409）。
     */
    public static OrderRuleViolation illegalTransition(String action) {
        return new OrderRuleViolation(Kind.CONFLICT, "ORDER_STATE_CONFLICT", "订单当前状态不允许" + action);
    }

    /**
     * 提交时订单没有任何订单行（422）。
     */
    public static OrderRuleViolation empty() {
        return new OrderRuleViolation(Kind.UNPROCESSABLE, "ORDER_EMPTY", "订单至少要有一行才能提交");
    }

    /**
     * 订单行数达到上限（422）。
     */
    public static OrderRuleViolation linesFull() {
        return new OrderRuleViolation(Kind.UNPROCESSABLE, "ORDER_LINES_FULL",
                "每张订单最多 " + Order.MAX_LINES + " 行");
    }

    /**
     * 同一商品已在订单中（422）。
     */
    public static OrderRuleViolation duplicateSku() {
        return new OrderRuleViolation(Kind.UNPROCESSABLE, "ORDER_LINE_DUPLICATE", "同一商品只能有一行");
    }

    /**
     * 订单总额超过上限（422）。
     */
    public static OrderRuleViolation totalExceeded() {
        return new OrderRuleViolation(Kind.UNPROCESSABLE, "ORDER_TOTAL_EXCEEDED", "订单总额超过上限");
    }
}
