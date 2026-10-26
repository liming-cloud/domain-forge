package @@PACKAGE@@.bootstrap;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 只扫描本服务基础设施中明确标记的 Mapper，查询与写入共用同一数据源。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "@@PACKAGE@@.infrastructure", annotationClass = Mapper.class)
public class PersistenceConfiguration {
}
