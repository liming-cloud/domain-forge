package org.domainforge.gateway.infrastructure.routing;

/**
 * 按客户端地址的令牌桶参数。
 *
 * @param replenishPerSecond 每秒补充的令牌数，1—10000
 * @param burst              桶容量（允许的突发请求数），不小于补充速率，至多 100000
 */
public record RateLimitSettings(int replenishPerSecond, int burst) {
    /**
     * 越界即整组配置不可应用。
     */
    public RateLimitSettings {
        if (replenishPerSecond < 1 || replenishPerSecond > 10_000)
            throw new IllegalArgumentException("replenish-per-second 须在 1—10000 之间");
        if (burst < replenishPerSecond || burst > 100_000)
            throw new IllegalArgumentException("burst 须不小于 replenish-per-second 且不超过 100000");
    }
}
