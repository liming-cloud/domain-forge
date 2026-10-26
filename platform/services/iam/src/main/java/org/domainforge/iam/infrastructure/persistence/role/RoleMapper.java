package org.domainforge.iam.infrastructure.persistence.role;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色持久化 SQL。
 */
@org.apache.ibatis.annotations.Mapper
public interface RoleMapper {
    RoleRow lock(long id);

    /**
     * 授予与自定义范围组织的左连接展开。
     */
    List<GrantRow> grants(long role);

    long codes(String code);

    int insert(RoleRow row);

    int update(@Param("row") RoleRow row, @Param("expected") long expected);

    int deleteGrants(long role);

    int insertGrants(@Param("role") long role, @Param("items") List<GrantRow> items);

    int insertGrantUnits(@Param("role") long role, @Param("items") List<GrantRow> items);

    int delete(long id);

    long assignments(long role);
}
