package org.domainforge.iam.infrastructure.persistence.operation;

/**
 * 结构协调锁：组织、菜单、目录各一行，由迁移固定建立。
 */
@org.apache.ibatis.annotations.Mapper
public interface CoordinationMapper {
    /**
     * 排他锁定协调行，返回行名；缺失返回 null。
     */
    String exclusive(String name);

    /**
     * 共享锁定协调行，允许并发读者、阻止排他写入。
     */
    String shared(String name);
}
