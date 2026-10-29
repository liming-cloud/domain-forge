package org.domainforge.persistence.additional;

import org.apache.ibatis.annotations.Mapper;

/**
 * 主数据源的测试 Mapper（SQL 在 mapper/additional-it/PrimaryRowMapper.xml）。
 */
@Mapper
public interface PrimaryRowMapper {
    void insert(String value);

    int count(String value);
}
