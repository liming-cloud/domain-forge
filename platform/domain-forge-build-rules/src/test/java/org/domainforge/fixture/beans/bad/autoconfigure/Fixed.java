package org.domainforge.fixture.beans.bad.autoconfigure;

import org.springframework.context.annotation.Bean;

/**
 * 反例：自动配置中的 Bean 没有 ConditionalOnMissingBean，用户无法替换。
 */
public class Fixed {
    @Bean
    Object cannotBeReplaced() {
        return new Object();
    }
}
