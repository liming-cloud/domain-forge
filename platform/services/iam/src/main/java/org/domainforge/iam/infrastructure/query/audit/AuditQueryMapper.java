package org.domainforge.iam.infrastructure.query.audit;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审计固定字段和记录ID分页，不接受动态SQL或无界导出。
 */
@org.apache.ibatis.annotations.Mapper
public interface AuditQueryMapper {
    /**
     * 只查询当前页，不对每条记录追加数据访问。
     */
    List<AuditProjection> list(@Param("after") long after, @Param("limit") int limit);
}
