package @@PACKAGE@@.application.contract.event;

/**
 * 集成事件：订单已提交（已提交的事实）。本服务对外承诺的公开合同，字段只增不改；不兼容的变化提升合同版本。
 *
 * @param orderId    订单 ID
 * @param ownerId    下单人账号 ID
 * @param totalCents 订单总额（分）
 */
public record OrderSubmitted(long orderId, long ownerId, long totalCents) {
    /**
     * 发布主题（普通主题，不保证顺序），在 deploy/rocketmq/topics.conf 中创建。
     */
    public static final String TOPIC = "@@ARTIFACT@@-orders";
    /**
     * 事件类型。
     */
    public static final String TYPE = "OrderSubmitted";
    /**
     * 合同版本。
     */
    public static final int VERSION = 1;
}
