package org.domainforge.servicecall.autoconfigure;

import feign.Capability;
import feign.Feign;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.domainforge.servicecall.internal.IdentityPropagation;
import org.domainforge.servicecall.internal.ResponseClassifier;
import org.domainforge.servicecall.internal.TransportFailures;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * 全部 Feign 客户端共用的调用合同：身份与关联信息传递、错误分类、不重试。超时等默认值见 service-call-defaults.yml。
 */
@AutoConfiguration
@ConditionalOnClass(Feign.class)
public class ServiceCallAutoConfiguration {
    /**
     * Same-Token 与用户令牌（链路上下文由 OpenFeign 的 Micrometer 观测传递）。
     */
    @Bean
    RequestInterceptor serviceCallIdentity() {
        return new IdentityPropagation();
    }

    /**
     * 非 2xx 响应的分类。
     */
    @Bean
    ErrorDecoder serviceCallErrors() {
        return new ResponseClassifier();
    }

    /**
     * 传输层异常的分类。
     */
    @Bean
    Capability serviceCallTransport() {
        return new TransportFailures();
    }

    /**
     * 默认不重试：结果未知的请求重放可能重复执行副作用，是否重试由调用方按接口幂等合同决定。
     */
    @Bean
    Retryer serviceCallRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
