package org.domainforge.messaging.api;

import java.util.regex.Pattern;

/**
 * 要可靠发布的集成事件。发布方选择公开字段写成 JSON，不携带聚合对象、凭据或全部表字段。
 *
 * @param topic    主题；须已在 RocketMQ 中按顺序方式创建（普通或 FIFO）
 * @param type     事件类型，过去式业务名称，如 {@code NoteCreated}
 * @param version  事件合同版本（不是聚合版本），从 1 开始
 * @param key      业务键（通常是聚合 ID），用于检索与关联
 * @param orderKey 顺序键：为空表示不需要顺序，发到普通主题；非空时同一顺序键按发布顺序投递，须发到 FIFO 主题
 * @param payload  JSON 文本
 */
public record OutgoingEvent(String topic, String type, int version, String key, String orderKey, String payload) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,126}");

    /**
     * 校验必填字段与长度；内容格式由发布方负责。
     */
    public OutgoingEvent {
        require(topic != null && NAME.matcher(topic).matches(), "主题名不合法");
        require(type != null && NAME.matcher(type).matches(), "事件类型不合法");
        require(version >= 1, "事件合同版本从 1 开始");
        require(bounded(key, 128), "业务键为 1—128 个字符");
        require(orderKey == null || bounded(orderKey, 128), "顺序键为空或 1—128 个字符");
        require(bounded(payload, 65_536), "事件内容为 1—65536 个字符的 JSON");
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    /**
     * 不需要顺序的事件。
     */
    public static OutgoingEvent unordered(String topic, String type, int version, String key, String payload) {
        return new OutgoingEvent(topic, type, version, key, null, payload);
    }
}
