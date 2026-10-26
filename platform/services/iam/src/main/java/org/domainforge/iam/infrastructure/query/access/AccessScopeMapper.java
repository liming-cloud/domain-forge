package org.domainforge.iam.infrastructure.query.access;

import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 数据范围计算 SQL：会话、账号、角色、授予、成员与组织有效性在同一语句快照中求值。
 */
@org.apache.ibatis.annotations.Mapper
public interface AccessScopeMapper {
    List<ScopeProjection> scopes(@Param("digest") String digest, @Param("permissions") Collection<String> permissions);
}
