package @@PACKAGE@@.infrastructure.messaging;

import @@PACKAGE@@.application.command.tally.RecordOrderSubmitted;
import org.domainforge.messaging.api.EventHandler;
import org.domainforge.messaging.api.ReceivedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 订阅 @@SOURCE@@ 的订单事件，把它的公开合同翻译为本服务的命令；不共享对方的类。
 *
 * <p>只处理 OrderSubmitted；主题上的其他事件类型忽略。认识的合同版本只有 1，更高版本抛出异常，
 * 重试用尽后进入死信并告警，提醒先升级本服务。
 */
@Component
public class SourceOrderSubmittedHandler implements EventHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RecordOrderSubmitted record;

    /**
     * 只依赖本服务的应用命令。
     */
    public SourceOrderSubmittedHandler(RecordOrderSubmitted record) {
        this.record = record;
    }

    @Override
    public String topic() {
        return "@@SOURCE@@-orders";
    }

    @Override
    public String group() {
        return "@@ARTIFACT@@-order-tally";
    }

    @Override
    public void handle(ReceivedEvent event) {
        if (!"OrderSubmitted".equals(event.type())) return;
        if (event.version() != 1) throw new IllegalStateException("不认识的 OrderSubmitted 合同版本：" + event.version());
        JsonNode payload = JSON.readTree(event.payload());
        record.record(Long.parseLong(payload.get("ownerId").asString()));
    }
}
