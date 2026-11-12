package @@PACKAGE@@.application.query.order;

import org.jspecify.annotations.Nullable;

/**
 * 订单列表投影；总额由订单行汇总（分）。
 */
public record OrderView(long id, long ownerId, @Nullable Long unitId, String status, long totalCents, long version) {
}
