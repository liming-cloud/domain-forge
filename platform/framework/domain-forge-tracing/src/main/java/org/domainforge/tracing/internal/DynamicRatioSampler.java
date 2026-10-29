package org.domainforge.tracing.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.domainforge.config.api.DynamicSettings;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 按当前配置的采样率决定新链路是否采样（按 traceId 比例，同一链路在各服务的决定一致）。
 * 外层由 {@code Sampler.parentBased} 包装：有上游上下文时沿用上游决定，只对根 span 使用本采样器。
 */
public final class DynamicRatioSampler implements Sampler {
    private final DynamicSettings<TracingSettings> settings;
    private final AtomicReference<Current> current = new AtomicReference<>();

    /**
     * 每次决策读取当前快照，采样率变化后下一次决策即生效。
     */
    public DynamicRatioSampler(DynamicSettings<TracingSettings> settings) {
        this.settings = settings;
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind spanKind,
                                       Attributes attributes, List<LinkData> parentLinks) {
        return delegate().shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    @Override
    public String getDescription() {
        return "DynamicRatioSampler{" + settings.current().samplingProbability() + "}";
    }

    private Sampler delegate() {
        double probability = settings.current().samplingProbability();
        Current cached = current.get();
        if (cached != null && cached.probability() == probability) return cached.sampler();
        Current fresh = new Current(probability, Sampler.traceIdRatioBased(probability));
        current.set(fresh);
        return fresh.sampler();
    }

    /**
     * 按采样率缓存的比例采样器。
     */
    private record Current(double probability, Sampler sampler) {
    }
}
