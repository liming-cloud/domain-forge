package org.domainforge.iam.application.query.audit;

import java.util.List;

/**
 * 审计查询入口必须具备iam:audit:read权限，数据量有界。
 */
public interface AuditQueries {
    /**
     * 按递增记录ID读取最多100项，不读取密码、令牌或原始请求。
     */
    List<AuditView> list(long after, int limit);
}
