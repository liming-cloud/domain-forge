package org.domainforge.iam.application.query.access;

import java.util.List;
import java.util.Map;

/**
 * 当前会话主体按精确权限读取数据范围；会话、账号、角色、成员与组织有效性在同一条 SQL 快照中计算。
 */
public interface AccessScopes {
    /**
     * 一次最多 32 个权限，每个必须是目录中需要数据范围的权限；单项最多 1000 个组织，超限明确失败。
     */
    int MAX_BATCH = 32;

    /**
     * 返回每个权限的范围；会话无效报告未认证，不返回空范围冒充放行或拒绝。
     */
    Map<String, AccessScope> scopes(String tokenDigest, List<String> permissions);
}
