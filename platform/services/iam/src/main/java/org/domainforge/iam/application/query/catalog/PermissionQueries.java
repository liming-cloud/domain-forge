package org.domainforge.iam.application.query.catalog;

import java.util.List;

/**
 * 权限目录查询；目录总量不超过 1000。
 */
public interface PermissionQueries {
    /**
     * 全部权限，按权限码排序。
     */
    List<PermissionView> list();
}
