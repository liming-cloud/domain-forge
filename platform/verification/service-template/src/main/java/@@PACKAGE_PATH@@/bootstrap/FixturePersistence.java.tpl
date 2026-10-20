package @@PACKAGE@@.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 只扫描验证服务拥有的持久化接口。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("@@PACKAGE@@.infrastructure.persistence")
public class FixturePersistence {}
