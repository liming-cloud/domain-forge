package @@PACKAGE@@.application.port.peer;

/**
 * @@PEER@@ 中当前用户可见的一条便笺，只取本服务用到的字段。
 *
 * @param id    便笺 ID（十进制字符串）
 * @param title 标题
 */
public record PeerNote(String id, String title) {
}
