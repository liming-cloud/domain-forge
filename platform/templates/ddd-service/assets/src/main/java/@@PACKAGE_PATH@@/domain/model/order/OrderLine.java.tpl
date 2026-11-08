package @@PACKAGE@@.domain.model.order;

import org.jmolecules.ddd.annotation.Entity;
import org.jmolecules.ddd.annotation.Identity;

/**
 * 订单行：只存在于订单聚合内部，经 {@link Order} 访问；行的身份在订单内唯一。
 */
@Entity
public record OrderLine(@Identity long id, String sku, int quantity, Money unitPrice) {
    /**
     * 单行数量上限。
     */
    public static final int MAX_QUANTITY = 999;
    /**
     * 单价上限（100 万元，单位：分）；与数量上限一起保证金额运算不会溢出。
     */
    public static final long MAX_UNIT_CENTS = 100_000_000L;

    /**
     * 恢复状态也校验商品、数量与单价。
     */
    public OrderLine {
        if (id <= 0) throw OrderRuleViolation.invalid("订单行身份无效");
        if (sku == null || sku.isBlank() || sku.strip().length() > 64)
            throw OrderRuleViolation.invalid("商品编码须为1至64个字符");
        if (quantity < 1 || quantity > MAX_QUANTITY)
            throw OrderRuleViolation.invalid("数量须为1至" + MAX_QUANTITY);
        if (unitPrice == null || unitPrice.cents() == 0 || unitPrice.cents() > MAX_UNIT_CENTS)
            throw OrderRuleViolation.invalid("单价须大于零且不超过100万元");
        sku = sku.strip();
    }

    /**
     * 本行小计。
     */
    public Money subtotal() {
        return unitPrice.times(quantity);
    }
}
