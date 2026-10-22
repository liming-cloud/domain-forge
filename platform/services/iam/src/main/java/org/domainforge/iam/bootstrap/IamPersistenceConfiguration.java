package org.domainforge.iam.bootstrap;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 仅扫描本服务明确标记的Mapper，查询与写入共用已配置的原生数据源。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(
    basePackages = {
      "org.domainforge.iam.infrastructure.persistence",
      "org.domainforge.iam.infrastructure.query"
    },
    annotationClass = Mapper.class)
public class IamPersistenceConfiguration {}
