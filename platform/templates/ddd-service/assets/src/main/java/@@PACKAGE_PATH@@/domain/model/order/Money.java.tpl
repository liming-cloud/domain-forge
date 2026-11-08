package @@PACKAGE@@.domain.model.order;

import org.jmolecules.ddd.annotation.ValueObject;

/**
 * 金额值对象：人民币，以分为单位的非负整数，不可变；按值判断相等。
 */
@ValueObject
public record Money(long cents) {
    /**
     * 金额不能为负；上限由持有它的订单与订单行约束。
     */
    public Money {
        if (cents < 0) throw OrderRuleViolation.invalid("金额不能为负");
    }

    /**
     * 零元。
     */
    public static Money zero() {
        return new Money(0);
    }

    /**
     * 两个金额之和。
     */
    public Money plus(Money other) {
        return new Money(cents + other.cents);
    }

    /**
     * 金额乘以数量。
     */
    public Money times(int quantity) {
        return new Money(cents * quantity);
    }

    /**
     * 是否超过另一金额。
     */
    public boolean exceeds(Money other) {
        return cents > other.cents;
    }
}
