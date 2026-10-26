package org.domainforge.iam.application.query.audit;

import java.util.List;

/**
 * 审计查询入口必须具备 iam:audit:read 权限，数据量有界。
 */
public interface AuditQueries {
    /**
     * 按记录 ID 递增读取 after 之后最多 limit（1 至 100）项，不读取密码、令牌或原始请求。
     */
    List<AuditView> list(long after, int limit);
}
