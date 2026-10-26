package @@PACKAGE@@.application.port.access;

import org.domainforge.shared.CurrentScope;

/**
 * 当前请求主体在本服务精确权限上的数据范围；由 IAM 计算，本服务把它映射为自己的查询条件与写入校验。
 */
public interface DataScopes {
    /**
     * 读取当前主体对一个需要数据范围的权限的范围；无授予返回空范围，依赖故障抛出访问不可用。
     */
    CurrentScope scope(String permission);
}
