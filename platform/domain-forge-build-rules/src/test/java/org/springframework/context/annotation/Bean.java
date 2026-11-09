package org.springframework.context.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 测试替身：与 Spring 同名的注解，用于验证自动配置的 Bean 可覆盖规则。
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {
}
