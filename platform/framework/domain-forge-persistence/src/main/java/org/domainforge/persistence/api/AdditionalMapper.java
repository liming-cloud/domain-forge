package org.domainforge.persistence.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 附加数据源的 Mapper 标记。主数据源按 {@code @Mapper} 扫描，附加数据源以
 * {@code @MapperScan(annotationClass = AdditionalMapper.class, sqlSessionFactoryRef = "<名称>SqlSessionFactory")}
 * 扫描各自的包，两者不会互相收录。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdditionalMapper {
}
