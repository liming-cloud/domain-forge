package org.domainforge.iam.infrastructure.query.catalog;

import org.domainforge.iam.application.query.catalog.PermissionView;

import java.util.List;

/**
 * 权限目录查询 SQL。
 */
@org.apache.ibatis.annotations.Mapper
public interface PermissionQueryMapper {
    List<PermissionView> list();
}
