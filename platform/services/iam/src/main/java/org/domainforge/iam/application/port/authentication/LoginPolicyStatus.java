package org.domainforge.iam.application.port.authentication;

import java.time.Instant;

/** 维护命令展示的非秘密策略身份与恢复截止，不暴露Redis键或安全凭据。 */
public record LoginPolicyStatus(
    String currentDigest, String configuredDigest, Instant blockedUntil) {}
