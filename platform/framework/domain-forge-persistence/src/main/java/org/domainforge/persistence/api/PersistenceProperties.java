package org.domainforge.persistence.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 持久化配置（前缀 {@code domainforge.persistence}）。事务超时用 Spring 的 {@code spring.transaction.default-timeout}。
 *
 * @param maxPageSize 分页上限，1 至 1000；服务仍须校验查询输入和任意自定义 SQL
 */
@ConfigurationProperties("domainforge.persistence")
public record PersistenceProperties(@DefaultValue("200") long maxPageSize) {
}
