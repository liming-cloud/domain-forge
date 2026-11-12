package @@PACKAGE@@.infrastructure.persistence.order;

import org.jspecify.annotations.Nullable;

/**
 * 订单表投影；订单行在 {@link OrderLineRow}。
 */
public record OrderRow(long id, long ownerId, @Nullable Long unitId, String status, long version) {
}
