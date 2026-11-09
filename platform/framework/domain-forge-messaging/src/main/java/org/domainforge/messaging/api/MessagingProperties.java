package org.domainforge.messaging.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 消息配置（前缀 {@code domainforge.messaging}）。需要连接 RocketMQ 时（启用中继或有处理者）必须配置 {@code endpoints}，
 * 缺失即启动失败。
 *
 * @param endpoints      RocketMQ Proxy 地址
 * @param requestTimeout 客户端请求超时
 * @param retentionHours 已发送与去重记录的保留小时数
 * @param outbox         发布端
 * @param relay          中继
 * @param consumer       消费端
 */
@ConfigurationProperties("domainforge.messaging")
public record MessagingProperties(
        @DefaultValue("") String endpoints,
        @DefaultValue("3s") Duration requestTimeout,
        @DefaultValue("168") long retentionHours,
        @DefaultValue Outbox outbox,
        @DefaultValue Relay relay,
        @DefaultValue Consumer consumer) {
    /**
     * 发布端。
     *
     * @param enabled 设为 true 启用 Outbox 发布
     */
    public record Outbox(boolean enabled) {
    }

    /**
     * 中继；{@code enabled=false} 只写 Outbox 不发送，仅供测试。
     *
     * @param enabled   是否发送 Outbox
     * @param interval  轮询间隔
     * @param batchSize 每批条数
     */
    public record Relay(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration interval,
            @DefaultValue("50") int batchSize) {
    }

    /**
     * 消费端；{@code enabled=false} 只装配处理者不连接消费组，仅供测试。
     *
     * @param enabled     是否连接消费组
     * @param maxAttempts 最大消费次数，超过后进入死信
     */
    public record Consumer(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5") int maxAttempts) {
    }
}
