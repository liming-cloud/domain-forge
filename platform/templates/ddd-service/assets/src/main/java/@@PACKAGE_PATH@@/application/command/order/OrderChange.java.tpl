package @@PACKAGE@@.application.command.order;

/**
 * 命令完成后的订单 ID、新版本与状态。
 */
public record OrderChange(long id, long version, String status) {
}
