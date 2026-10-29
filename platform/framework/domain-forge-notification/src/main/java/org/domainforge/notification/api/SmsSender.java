package org.domainforge.notification.api;

/**
 * 发送短信：同步调用服务商，不自动重试（避免重复短信与重复扣费）。需要可靠送达的业务通知，
 * 由业务经 Outbox 发布事件、在消费方调用本接口，让消息组件负责重投。
 */
public interface SmsSender {
    /**
     * 发送一条模板短信。
     *
     * @return 服务商的回执
     * @throws SmsRejected                        号码无效或被服务商频控
     * @throws NotificationUnavailableException 服务商不可达、超时、服务端错误或账号与模板配置错误
     */
    SmsReceipt send(SmsMessage message);
}
