package @@PACKAGE@@.infrastructure.persistence.order;

/**
 * 订单行表投影。
 */
public record OrderLineRow(long id, String sku, int quantity, long unitPriceCents) {
}
