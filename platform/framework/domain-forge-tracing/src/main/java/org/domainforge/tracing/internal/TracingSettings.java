package org.domainforge.tracing.internal;

/**
 * 链路追踪的动态配置组 {@code domainforge.tracing}：采样率可在 Nacos 中调整，立即生效。
 *
 * @param samplingProbability 新链路（入口或定时任务开启的链路）的采样率，0—1；已有上游采样决定的请求沿用上游决定
 */
public record TracingSettings(double samplingProbability) {
    /**
     * 越界即整组配置不可应用。
     */
    public TracingSettings {
        if (!(samplingProbability >= 0 && samplingProbability <= 1))
            throw new IllegalArgumentException("sampling-probability 须在 0—1 之间");
    }
}
