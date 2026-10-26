package org.domainforge.iam.infrastructure.query.access;

/**
 * 范围计算的展开行：kind 为 SUBJECT、PERMISSION、ALL、SELF 或 UNIT。
 */
public record ScopeProjection(String kind, String permission, Long unitId) {
}
