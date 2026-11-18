package org.apache.ibatis.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 测试替身：与 MyBatis 同名的 Mapper 标记注解，用于验证启动层持久化规则。
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Mapper {
}
