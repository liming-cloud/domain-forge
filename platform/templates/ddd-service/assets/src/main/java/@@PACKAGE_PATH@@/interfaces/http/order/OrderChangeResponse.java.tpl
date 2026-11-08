package @@PACKAGE@@.interfaces.http.order;

import @@PACKAGE@@.application.command.order.OrderChange;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 命令完成后的 ID、新版本与状态。
 */
public record OrderChangeResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long id, long version, String status) {
    static OrderChangeResponse of(OrderChange change) {
        return new OrderChangeResponse(change.id(), change.version(), change.status());
    }
}
