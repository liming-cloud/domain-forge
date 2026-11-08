package @@PACKAGE@@.application.query.digest;

/**
 * 订单摘要：对方返回的可见订单数与总额。
 *
 * @param count      可见订单数
 * @param totalCents 这些订单的总额（分）
 */
public record OrderDigest(int count, long totalCents) {
}
