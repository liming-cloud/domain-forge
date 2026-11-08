package @@PACKAGE@@.application.query.order;

/**
 * 订单列表投影；总额由订单行汇总（分）。
 */
public record OrderView(long id, long ownerId, Long unitId, String status, long totalCents, long version) {
}
