package org.domainforge.iam.infrastructure.persistence.session;

import java.time.Instant;

/** SQL防护状态及共享策略；允许与截止均以数据库时间计算。 */
public record ProtectionRow(
    String generation,
    boolean allowed,
    String policyDigest,
    int accountAttempts,
    int accountWindowSeconds,
    int addressAttempts,
    int addressWindowSeconds,
    Instant blockedUntil) {}
