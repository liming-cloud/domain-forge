package org.domainforge.iam.infrastructure.persistence.audit;

import org.apache.ibatis.annotations.Param;

/**
 * 审计存储只接收脱敏事实，正常记录与人工补录分别声明来源。
 */
@org.apache.ibatis.annotations.Mapper
public interface AuditMapper {
    /**
     * 审计语句有500ms上限，防止独立观测拖成长事务。
     */
    String budget();

    /**
     * 幂等落库不因同一请求重放制造重复审计；不写完整输入或凭据。
     */
    int insert(
            @Param("operation") String operation,
            @Param("actor") String actor,
            @Param("target") String target,
            @Param("action") String action,
            @Param("outcome") String outcome);
}
