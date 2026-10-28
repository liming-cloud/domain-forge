package @@PACKAGE@@.infrastructure.persistence.tally;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 便笺统计表；SQL 在 mapper/tally/NoteTallyMapper.xml。
 */
@Mapper
public interface NoteTallyMapper {
    /**
     * 不存在则建为 1，存在则加一。
     */
    int increment(@Param("ownerId") long ownerId);

    /**
     * 某账号的便笺数，没有记录为 null。
     */
    Long count(@Param("ownerId") long ownerId);
}
