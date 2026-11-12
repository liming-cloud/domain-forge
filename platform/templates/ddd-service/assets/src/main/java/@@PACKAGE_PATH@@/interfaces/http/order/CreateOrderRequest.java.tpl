package @@PACKAGE@@.interfaces.http.order;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

/**
 * 新建草稿订单；unitId 为空表示不归属组织，lines 可以为空，之后再增加订单行。
 */
public record CreateOrderRequest(
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable Long unitId, @Nullable List<LineRequest> lines) {
    /**
     * 缺省的订单行按空列表处理。
     */
    public CreateOrderRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
