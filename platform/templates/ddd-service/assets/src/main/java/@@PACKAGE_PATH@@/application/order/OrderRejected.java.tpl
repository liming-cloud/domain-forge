package @@PACKAGE@@.application.order;

import org.domainforge.shared.BusinessRuleViolation;

import java.io.Serial;

/**
 * 订单用例已判定的业务拒绝：带稳定错误码，由 web-support 按类别统一转为 Problem Details（404／409／422／400），
 * 入口不需要登记映射。
 */
public final class OrderRejected extends BusinessRuleViolation {
    @Serial
    private static final long serialVersionUID = 1L;

    private OrderRejected(Kind kind, String code, String message) {
        super(kind, code, message);
    }

    /**
     * 对象不存在或不在可见范围内（404）。
     */
    public static OrderRejected notFound(String code, String message) {
        return new OrderRejected(Kind.NOT_FOUND, code, message);
    }

    /**
     * 当前状态不允许该操作，或对象已被他人修改（409）。
     */
    public static OrderRejected conflict(String code, String message) {
        return new OrderRejected(Kind.CONFLICT, code, message);
    }

    /**
     * 订单版本与请求不符，或保存时已被他人修改（409 {@code VERSION_CONFLICT}，与 IAM 相同）。
     */
    public static OrderRejected versionConflict() {
        return conflict("VERSION_CONFLICT", "订单已被修改，请刷新后重试");
    }

    /**
     * 输入取值无效（400）。
     */
    public static OrderRejected invalid(String code, String message) {
        return new OrderRejected(Kind.INVALID_REQUEST, code, message);
    }

    /**
     * 业务规则不满足（422）。
     */
    public static OrderRejected unprocessable(String code, String message) {
        return new OrderRejected(Kind.UNPROCESSABLE, code, message);
    }
}
