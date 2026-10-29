package org.domainforge.tracing.autoconfigure;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.domainforge.config.api.DynamicSettingsBinder;
import org.domainforge.tracing.internal.DynamicRatioSampler;
import org.domainforge.tracing.internal.TraceIdServletFilter;
import org.domainforge.tracing.internal.TraceIdWebFilter;
import org.domainforge.tracing.internal.TracingSettings;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 链路追踪装配：动态采样率（父决定优先），以及在响应头返回 traceId。导出由 Spring Boot 的 OTLP 导出器负责，
 * 只在配置了 {@code management.opentelemetry.tracing.export.otlp.endpoint} 时启用。
 */
@AutoConfiguration(beforeName = "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure"
        + ".OpenTelemetryTracingAutoConfiguration")
public class TracingAutoConfiguration {
    /**
     * 有上游上下文时沿用上游的采样决定，根 span 按当前配置的比例采样。
     */
    @Bean
    Sampler domainforgeSampler(DynamicSettingsBinder binder) {
        return Sampler.parentBased(new DynamicRatioSampler(binder.bind("domainforge.tracing", TracingSettings.class)));
    }

    /**
     * Servlet 应用的 traceId 响应头。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class Servlet {
        @Bean
        TraceIdServletFilter traceIdServletFilter(Tracer tracer) {
            return new TraceIdServletFilter(tracer);
        }
    }

    /**
     * 响应式应用的 traceId 响应头。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class Reactive {
        @Bean
        TraceIdWebFilter traceIdWebFilter() {
            return new TraceIdWebFilter();
        }
    }
}
