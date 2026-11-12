package @@PACKAGE@@.interfaces.http.order;

import org.jspecify.annotations.Nullable;

import @@PACKAGE@@.application.query.order.OrderView;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 订单的 HTTP 表示；ID 以十进制字符串传输，金额以分为单位。
 */
public record OrderResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long id,
        @JsonFormat(shape = JsonFormat.Shape.STRING) long ownerId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable Long unitId,
        String status,
        long totalCents,
        long version) {
    static OrderResponse of(OrderView view) {
        return new OrderResponse(view.id(), view.ownerId(), view.unitId(), view.status(), view.totalCents(),
                view.version());
    }
}
