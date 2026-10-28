package @@PACKAGE@@.application.contract.event;

/**
 * 集成事件：便笺已创建（已提交的事实）。本服务对外承诺的公开合同，字段只增不改；不兼容的变化提升合同版本。
 *
 * @param noteId  便笺 ID
 * @param ownerId 创建者账号 ID
 */
public record NoteCreated(long noteId, long ownerId) {
    /**
     * 发布主题（普通主题，不保证顺序），在 deploy/rocketmq/topics.conf 中创建。
     */
    public static final String TOPIC = "@@ARTIFACT@@-notes";

    /**
     * 事件类型。
     */
    public static final String TYPE = "NoteCreated";

    /**
     * 合同版本。
     */
    public static final int VERSION = 1;
}
