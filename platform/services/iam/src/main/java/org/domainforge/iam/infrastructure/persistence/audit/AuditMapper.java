package org.domainforge.iam.infrastructure.persistence.audit;

import org.apache.ibatis.annotations.Param;

/**
 * 审计存储只接收脱敏事实，在调用方业务事务中写入。
 */
@org.apache.ibatis.annotations.Mapper
public interface AuditMapper {
    /**
     * 写入一条审计；不写完整输入或凭据，发生时间取数据库时钟。
     */
    int insert(
            @Param("id") long id,
            @Param("actor") String actor,
            @Param("target") long target,
            @Param("action") String action);
}
