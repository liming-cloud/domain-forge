package org.apache.ibatis.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 测试替身：与 MyBatis 同名的注解，用于验证 SQL 注解规则。
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Select {
    /**
     * SQL 文本。
     */
    String[] value();
}
