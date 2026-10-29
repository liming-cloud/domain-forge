package org.domainforge.notification.api;

/**
 * 服务商受理短信后的回执。受理不等于送达，送达状态以服务商的回执查询为准。
 *
 * @param messageId 服务商的发送回执 ID（阿里云为 BizId）
 */
public record SmsReceipt(String messageId) {
}
