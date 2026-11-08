package @@PACKAGE@@.interfaces.http.order;

/**
 * 提交、支付、取消订单时携带读取时的版本。
 */
public record OrderVersionRequest(long expectedVersion) {
}
