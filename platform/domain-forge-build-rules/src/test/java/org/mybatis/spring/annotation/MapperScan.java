package org.mybatis.spring.annotation;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 测试替身：与 MyBatis-Spring 同名的扫描注解，启动层可以用它装配 Mapper。
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface MapperScan {
    /**
     * 扫描的包。
     */
    String[] basePackages() default {};

    /**
     * 只扫描带此注解的接口。
     */
    Class<? extends Annotation> annotationClass() default Annotation.class;

    /**
     * 创建 Mapper 的工厂；默认值也算作被注解类的依赖。
     */
    Class<?> factoryBean() default org.mybatis.spring.mapper.MapperFactoryBean.class;
}
