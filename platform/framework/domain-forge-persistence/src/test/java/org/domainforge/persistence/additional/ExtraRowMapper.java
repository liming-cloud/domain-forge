package org.domainforge.persistence.additional;

import org.domainforge.persistence.api.AdditionalMapper;

/**
 * 附加数据源 extra 的测试 Mapper（SQL 在 mapper-extra/ExtraRowMapper.xml）。
 */
@AdditionalMapper
public interface ExtraRowMapper {
    void insert(String value);

    int count(String value);

    String database();
}
