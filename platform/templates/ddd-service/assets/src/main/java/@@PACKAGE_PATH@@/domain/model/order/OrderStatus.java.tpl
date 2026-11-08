package @@PACKAGE@@.domain.model.order;

/**
 * 订单状态：草稿 → 已提交 → 已支付；草稿与已提交可取消。已支付与已取消是终态。
 */
public enum OrderStatus {
    DRAFT, SUBMITTED, PAID, CANCELLED
}
