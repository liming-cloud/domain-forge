package org.domainforge.iam.infrastructure.query.account;

/**
 * 账号详情的关联行：kind 为 role 或 member，primary 只对成员有意义。
 */
public record AccountRelationProjection(String kind, long targetId, boolean primary) {
}
