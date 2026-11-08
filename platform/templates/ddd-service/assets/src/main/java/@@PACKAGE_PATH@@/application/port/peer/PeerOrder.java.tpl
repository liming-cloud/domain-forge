package @@PACKAGE@@.application.port.peer;

/**
 * @@PEER@@ 中当前用户可见的一条订单，只取本服务用到的字段。
 *
 * @param id         订单 ID（十进制字符串）
 * @param status     订单状态
 * @param totalCents 订单总额（分）
 */
public record PeerOrder(String id, String status, long totalCents) {
}
