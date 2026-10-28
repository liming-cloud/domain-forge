package @@PACKAGE@@.application.port.tally;

/**
 * 本服务维护的便笺统计（读模型）：由 @@SOURCE@@ 发布的 NoteCreated 事件累加，不是 @@SOURCE@@ 数据的副本。
 */
public interface NoteTallies {
    /**
     * 某账号的便笺数加一。
     */
    void increment(long ownerId);

    /**
     * 某账号的便笺数；没有记录为 0。
     */
    long count(long ownerId);
}
