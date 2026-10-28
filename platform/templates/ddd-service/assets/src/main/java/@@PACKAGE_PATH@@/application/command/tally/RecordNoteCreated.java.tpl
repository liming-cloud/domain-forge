package @@PACKAGE@@.application.command.tally;

import @@PACKAGE@@.application.port.tally.NoteTallies;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费示例的命令：@@SOURCE@@ 的一个便笺已创建，累加创建者的便笺数。
 *
 * <p>由消息组件在本地事务内调用，去重记录与累加一起提交，同一事件只累加一次。
 */
@Service
public class RecordNoteCreated {
    private final NoteTallies tallies;

    /**
     * 只依赖统计端口。
     */
    public RecordNoteCreated(NoteTallies tallies) {
        this.tallies = tallies;
    }

    /**
     * 加入调用方（消息组件）的事务。
     */
    @Transactional
    public void record(long ownerId) {
        tallies.increment(ownerId);
    }
}
