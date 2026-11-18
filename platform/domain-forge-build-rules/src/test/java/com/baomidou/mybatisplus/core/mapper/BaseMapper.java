package com.baomidou.mybatisplus.core.mapper;

/**
 * 测试替身：与 MyBatis-Plus 同名的通用 Mapper，用于验证启动层持久化规则。
 */
public interface BaseMapper<T> {
    /**
     * 按主键查询。
     */
    T selectById(Object id);
}
