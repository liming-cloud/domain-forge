package com.baomidou.mybatisplus.core.conditions;

/**
 * 测试替身：与 MyBatis-Plus 同名的条件构造器，用于验证原始 SQL 拼接规则。
 */
public class AbstractWrapper {
    /**
     * 拼接原始 SQL 片段。
     */
    public AbstractWrapper apply(String sql) {
        return this;
    }

    /**
     * 普通条件。
     */
    public AbstractWrapper eq(String column, Object value) {
        return this;
    }
}
