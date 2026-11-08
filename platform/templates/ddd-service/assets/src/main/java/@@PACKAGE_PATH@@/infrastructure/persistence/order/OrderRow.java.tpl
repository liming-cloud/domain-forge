package @@PACKAGE@@.infrastructure.persistence.order;

/**
 * 订单表投影；订单行在 {@link OrderLineRow}。
 */
public record OrderRow(long id, long ownerId, Long unitId, String status, long version) {
}
