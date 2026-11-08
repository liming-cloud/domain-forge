package @@PACKAGE@@.interfaces.http.order;

/**
 * 给草稿订单增加一行，须携带读取时的版本。
 */
public record AddLineRequest(long expectedVersion, String sku, int quantity, long unitPriceCents) {
}
