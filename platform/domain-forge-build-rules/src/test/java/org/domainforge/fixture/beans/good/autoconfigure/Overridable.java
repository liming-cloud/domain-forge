package org.domainforge.fixture.beans.good.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 合法的自动配置：公开 Bean 都带 ConditionalOnMissingBean，包括嵌套配置类中的 Bean。
 */
public class Overridable {
    @Bean
    @ConditionalOnMissingBean
    Object first() {
        return new Object();
    }

    static class Nested {
        @Bean
        @ConditionalOnMissingBean
        Object second() {
            return new Object();
        }
    }
}
