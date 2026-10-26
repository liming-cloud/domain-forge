package org.domainforge.iam.infrastructure.persistence.menu;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 菜单持久化 SQL。
 */
@org.apache.ibatis.annotations.Mapper
public interface MenuMapper {
    MenuRow lock(long id);

    List<Long> ancestors(long id);

    long codes(String code);

    int insert(MenuRow row);

    int update(@Param("row") MenuRow row, @Param("expected") long expected);

    int delete(long id);

    long children(long id);
}
