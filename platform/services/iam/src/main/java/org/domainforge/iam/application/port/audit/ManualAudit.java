package org.domainforge.iam.application.port.audit;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 人工补录事实明确区分真实补录者、原操作和未知字段，不接收原始请求或秘密。 */
public record ManualAudit(
    UUID originalOperationId,
    UUID targetId,
    String action,
    Instant occurredAt,
    String provenance,
    Set<String> missingFields) {
  private static final Set<String> ACTIONS =
      Set.of(
          "administrator.initialize",
          "invitation.issue",
          "invitation.accept",
          "account.permissions",
          "account.state",
          "account.password",
          "session.logout");
  private static final Set<String> MISSING =
      Set.of("occurredAt", "originalActor", "originalOutcome");

  /** 来源仅允许工单标识或证据摘要，避免把任意秘密文本误存为说明。 */
  public ManualAudit {
    if (originalOperationId == null
        || targetId == null
        || action == null
        || !ACTIONS.contains(action)
        || !validProvenance(provenance)
        || !validMissing(missingFields, occurredAt)) {
      throw new IllegalArgumentException("补录需要明确来源和真实缺失字段");
    }
    missingFields = Set.copyOf(missingFields);
  }

  private static boolean validProvenance(String provenance) {
    return provenance != null
        && provenance.matches("(?:ticket:[A-Z0-9-]{1,64}|sha256:[a-f0-9]{64})");
  }

  private static boolean validMissing(Set<String> fields, Instant occurredAt) {
    return fields != null
        && fields.stream().allMatch(field -> field != null && MISSING.contains(field))
        && fields.contains("originalActor")
        && fields.contains("originalOutcome")
        && (occurredAt == null) == fields.contains("occurredAt");
  }
}
