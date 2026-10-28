package @@PACKAGE@@.infrastructure.messaging;

import @@PACKAGE@@.application.command.tally.RecordNoteCreated;
import org.domainforge.messaging.api.EventHandler;
import org.domainforge.messaging.api.ReceivedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 订阅 @@SOURCE@@ 的便笺事件，把它的公开合同翻译为本服务的命令；不共享对方的类。
 *
 * <p>只处理 NoteCreated；主题上的其他事件类型忽略。认识的合同版本只有 1，更高版本抛出异常，
 * 重试用尽后进入死信并告警，提醒先升级本服务。
 */
@Component
public class SourceNoteCreatedHandler implements EventHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RecordNoteCreated record;

    /**
     * 只依赖本服务的应用命令。
     */
    public SourceNoteCreatedHandler(RecordNoteCreated record) {
        this.record = record;
    }

    @Override
    public String topic() {
        return "@@SOURCE@@-notes";
    }

    @Override
    public String group() {
        return "@@ARTIFACT@@-note-tally";
    }

    @Override
    public void handle(ReceivedEvent event) {
        if (!"NoteCreated".equals(event.type())) return;
        if (event.version() != 1) throw new IllegalStateException("不认识的 NoteCreated 合同版本：" + event.version());
        JsonNode payload = JSON.readTree(event.payload());
        record.record(Long.parseLong(payload.get("ownerId").asString()));
    }
}
