package org.domainforge.tracing.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 采样率变化立即生效；有上游上下文时沿用上游的采样决定。
 */
class DynamicRatioSamplerTest {
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static SamplingDecision decide(Sampler sampler, Context parent) {
        return sampler.shouldSample(parent, TRACE, "span", SpanKind.SERVER, Attributes.empty(), List.of())
                .getDecision();
    }

    private static Context parent(boolean sampled) {
        var context = SpanContext.createFromRemoteParent(TRACE, "00f067aa0ba902b7",
                sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(), TraceState.getDefault());
        return Context.root().with(Span.wrap(context));
    }

    @Test
    void probabilityChangesApplyToTheNextDecision() {
        var settings = new AtomicReference<>(new TracingSettings(0));
        var sampler = Sampler.parentBased(new DynamicRatioSampler(settings::get));
        assertEquals(SamplingDecision.DROP, decide(sampler, Context.root()));
        settings.set(new TracingSettings(1));
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, decide(sampler, Context.root()));
    }

    @Test
    void upstreamDecisionWins() {
        var sampler = Sampler.parentBased(new DynamicRatioSampler(() -> new TracingSettings(0)));
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, decide(sampler, parent(true)));
        var always = Sampler.parentBased(new DynamicRatioSampler(() -> new TracingSettings(1)));
        assertEquals(SamplingDecision.DROP, decide(always, parent(false)));
    }

    @Test
    void probabilityIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> new TracingSettings(1.5));
        assertThrows(IllegalArgumentException.class, () -> new TracingSettings(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new TracingSettings(Double.NaN));
    }
}
