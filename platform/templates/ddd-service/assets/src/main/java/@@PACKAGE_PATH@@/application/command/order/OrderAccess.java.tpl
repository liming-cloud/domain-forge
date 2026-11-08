package @@PACKAGE@@.application.command.order;

import @@PACKAGE@@.domain.model.order.Order;
import org.domainforge.shared.CurrentScope;

/**
 * 订单的数据范围授权：ALL 可见全部；本人范围可见自己下的单；组织范围可见归属其中组织的单。
 *
 * <p>这是“谁能访问”的授权决定，范围事实来自 IAM，不是订单自身的业务规则，因此在应用层核对。
 */
public final class OrderAccess {
    private OrderAccess() {
    }

    /**
     * 订单是否在范围内。
     */
    public static boolean within(Order order, long account, CurrentScope scope) {
        return scope.all() || (scope.self() && order.ownerId() == account)
                || (order.unitId() != null && scope.unitIds().contains(order.unitId()));
    }

    /**
     * 能否新建：不归属组织的订单需要 ALL 或本人范围；归属组织时该组织须在范围内（本人范围不能扩展到任意组织）。
     */
    public static boolean creatable(Long unitId, CurrentScope scope) {
        return scope.all() || (unitId == null ? scope.self() : scope.unitIds().contains(unitId));
    }
}
