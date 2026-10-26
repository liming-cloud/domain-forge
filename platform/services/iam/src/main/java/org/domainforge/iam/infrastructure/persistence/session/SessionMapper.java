package org.domainforge.iam.infrastructure.persistence.session;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * 当前身份一次关联查询明确检查状态、期限、撤销和凭据版本，禁止旧快照缓存。
 */
@org.apache.ibatis.annotations.Mapper
public interface SessionMapper {
    /**
     * 本事务身份查询的数据库执行预算500ms。
     */
    String budget();

    /**
     * 主库新语句读当前状态；单纯Redis仍有效不足以返回身份。
     */
    IdentityRow current(String digest);

    /**
     * 原生令牌在SQL只保存摘要，期限由调用方在数据库时钟基础上产生。
     */
    int insert(
            @Param("digest") String digest,
            @Param("account") long account,
            @Param("version") long version,
            @Param("expires") Instant expires);

    /**
     * 当前会话退出可重放，其他许可不受影响。
     */
    int revoke(String digest);
}
