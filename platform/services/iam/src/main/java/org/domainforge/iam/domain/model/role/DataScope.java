package org.domainforge.iam.domain.model.role;

/**
 * 授予时绑定的数据范围；NOT_APPLICABLE 只用于目录声明的非数据操作，不等于 ALL。
 */
public enum DataScope {
    NOT_APPLICABLE,
    SELF,
    DEPARTMENT,
    DEPARTMENT_AND_DESCENDANTS,
    CUSTOM_DEPARTMENTS,
    ALL
}
