package org.domainforge.iam.infrastructure.query.role;

import org.domainforge.iam.application.query.role.RoleView;

import java.util.List;

/**
 * 角色查询 SQL。
 */
@org.apache.ibatis.annotations.Mapper
public interface RoleQueryMapper {
    List<RoleView> list();

    RoleView find(long id);

    List<RoleGrantProjection> grants(long id);
}
