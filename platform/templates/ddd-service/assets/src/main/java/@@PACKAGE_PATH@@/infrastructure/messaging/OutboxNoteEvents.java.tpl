package @@PACKAGE@@.infrastructure.messaging;

import @@PACKAGE@@.application.contract.event.NoteCreated;
import @@PACKAGE@@.application.port.event.NoteEvents;
import org.domainforge.messaging.api.EventPublisher;
import org.domainforge.messaging.api.OutgoingEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 经消息组件的同事务 Outbox 发布；ID 以十进制字符串写入，与 HTTP 合同一致。
 */
@Component
public class OutboxNoteEvents implements NoteEvents {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EventPublisher publisher;

    /**
     * 只依赖消息组件的公开接口。
     */
    public OutboxNoteEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void created(NoteCreated event) {
        String payload = JSON.writeValueAsString(Map.of(
                "noteId", Long.toString(event.noteId()), "ownerId", Long.toString(event.ownerId())));
        publisher.publish(OutgoingEvent.unordered(
                NoteCreated.TOPIC, NoteCreated.TYPE, NoteCreated.VERSION, Long.toString(event.noteId()), payload));
    }
}
