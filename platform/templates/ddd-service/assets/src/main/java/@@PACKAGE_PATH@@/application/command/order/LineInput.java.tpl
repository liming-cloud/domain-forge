package @@PACKAGE@@.application.command.order;

/**
 * 订单行的输入：商品编码、数量与单价（分）；取值是否有效由订单聚合判断。
 */
public record LineInput(String sku, int quantity, long unitPriceCents) {
}
