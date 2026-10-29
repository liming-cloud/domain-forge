package org.domainforge.tracing.internal;

/**
 * 链路相关的响应头。
 */
public final class TraceHeaders {
    /**
     * 返回给调用方的 traceId。
     */
    public static final String TRACE_ID = "X-Trace-Id";

    private TraceHeaders() {
    }
}
