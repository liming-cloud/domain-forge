package @@PACKAGE@@.interfaces.http.order;

import @@PACKAGE@@.application.command.order.LineInput;

/**
 * 订单行：商品编码、数量与单价（分）。
 */
public record LineRequest(String sku, int quantity, long unitPriceCents) {
    LineInput toInput() {
        return new LineInput(sku, quantity, unitPriceCents);
    }
}
