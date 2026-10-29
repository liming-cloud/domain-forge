package org.domainforge.messaging.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.HashMap;
import java.util.Map;

/**
 * 跨越 Outbox 与消息的链路上下文（W3C traceparent）：发布时记下当前链路，中继发送与消费时以它为父继续。
 *
 * <p>服务未启用链路追踪时使用 {@link Tracer#NOOP} 与 {@link Propagator#NOOP}，行为不变、不记录上下文。
 */
public final class TraceContexts {
    static final String TRACE_PARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    /**
     * 使用服务的 Micrometer Tracing 组件。
     */
    public TraceContexts(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * 当前链路的 traceparent；没有当前 span 时为空。
     */
    String current() {
        Span span = tracer.currentSpan();
        return span == null ? null : inject(span);
    }

    /**
     * 以给定 traceparent 为父开启子 span；没有父上下文时开启新链路。
     */
    Span start(String traceParent, String name, Span.Kind kind) {
        if (traceParent == null) return tracer.nextSpan().name(name).start();
        Map<String, String> carrier = Map.of(TRACE_PARENT, traceParent);
        return propagator.extract(carrier, Map::get).name(name).kind(kind).start();
    }

    /**
     * 把 span 的上下文写成 traceparent。
     */
    String inject(Span span) {
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACE_PARENT);
    }

    /**
     * 让 span 成为当前 span（日志带其 traceId）。
     */
    Tracer.SpanInScope scope(Span span) {
        return tracer.withSpan(span);
    }
}
