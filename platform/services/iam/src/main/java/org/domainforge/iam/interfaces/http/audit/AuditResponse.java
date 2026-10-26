package org.domainforge.iam.interfaces.http.audit;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.domainforge.iam.application.query.audit.AuditView;

import java.time.Instant;

/**
 * 审计记录的 HTTP 表示；ID 以十进制字符串传输。
 */
public record AuditResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long id,
        String actor,
        @JsonFormat(shape = JsonFormat.Shape.STRING) long targetId,
        String action,
        Instant occurredAt) {
    /**
     * 从查询视图转换。
     */
    static AuditResponse of(AuditView view) {
        return new AuditResponse(view.id(), view.actor(), view.targetId(), view.action(), view.occurredAt());
    }
}
