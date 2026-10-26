package org.springframework.stereotype;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 测试替身：与 Spring 同名的注解，用于验证应用层白名单。
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Service {
}
