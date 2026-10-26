package org.domainforge.iam.infrastructure.query.account;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 单次业务投影SQL，不使用总数查询或逐账号补读。
 */
@org.apache.ibatis.annotations.Mapper
public interface AccountQueryMapper {
    /**
     * 本人查询只按可信身份绑定主键。
     */
    AccountProjection self(long id);

    /**
     * 固定排序和绑定游标，不能将客户端字段作为SQL排序片段。
     */
    List<AccountProjection> list(@Param("after") long after, @Param("limit") int limit);

    /**
     * 账号的角色分配与成员归属合并为一次查询。
     */
    List<AccountRelationProjection> relations(long id);
}
