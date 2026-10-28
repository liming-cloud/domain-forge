package org.domainforge.messaging.api;

/**
 * 收到的集成事件。
 *
 * @param id       事件 ID，发布方生成，重发不变
 * @param type     事件类型
 * @param version  事件合同版本
 * @param key      业务键
 * @param payload  JSON 文本
 * @param attempt  第几次投递，从 1 开始
 */
public record ReceivedEvent(String id, String type, int version, String key, String payload, int attempt) {
}
