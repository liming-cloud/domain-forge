package org.domainforge.gateway.infrastructure.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.same.SaSameUtil;
import org.domainforge.cache.api.LockOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Same-Token 定时刷新（Sa-Token 文档推荐的做法：由专门的定时任务按固定间隔调用 {@code SaSameUtil.refreshToken()}，
 * 间隔低于有效期；集群中不能多处重复刷新）。
 *
 * <p>每个网关实例都按间隔触发，但在同一把分布式锁内先看当前值的“年龄”（有效期减剩余时间）：不足一个间隔说明其他实例
 * 刚刷新过，跳过。因此一个间隔内只刷新一次，旧值作为“上一个值”继续被后端接受到下次刷新。启动后立即检查一次，
 * 保证接收流量前已有值；锁或 Redis 不可用时记录错误，下次再试。
 */
@Component
public class SameTokenRotation {
    private static final Logger LOG = LoggerFactory.getLogger(SameTokenRotation.class);
    private static final String LOCK = "gateway:same-token-rotation";
    private static final long TOLERANCE_SECONDS = 30;

    private final LockOperations locks;
    private final Duration interval;

    /**
     * 跨实例互斥由 cache 组件的分布式锁提供。
     */
    public SameTokenRotation(
            LockOperations locks,
            @Value("${domainforge.gateway.same-token-refresh-interval:PT30M}") Duration interval) {
        this.locks = locks;
        this.interval = interval;
    }

    /**
     * 是否需要刷新：值不存在，或已存在不少于一个间隔（容差 30 秒，吸收各实例触发时间的偏差）。
     *
     * @param remainingSeconds 剩余秒数；不存在为 -2，永不过期为 -1
     * @param timeoutSeconds   配置的有效期
     * @param intervalSeconds  刷新间隔
     */
    static boolean due(long remainingSeconds, long timeoutSeconds, long intervalSeconds) {
        if (remainingSeconds == -2) return true;
        if (remainingSeconds == -1) return false;
        return timeoutSeconds - remainingSeconds >= intervalSeconds - TOLERANCE_SECONDS;
    }

    /**
     * 按固定间隔检查并刷新。
     */
    @Scheduled(initialDelay = 0, fixedDelayString = "${domainforge.gateway.same-token-refresh-interval:PT30M}")
    public void refreshIfDue() {
        try {
            locks.withLock(LOCK, Duration.ofSeconds(5), () -> {
                long remaining = SaManager.getSaSameTemplate().getTokenTimeout();
                long timeout = SaManager.getConfig().getSameTokenTimeout();
                if (!due(remaining, timeout, interval.toSeconds())) return;
                SaSameUtil.refreshToken();
                LOG.info("gateway_same_token_refreshed");
            });
        } catch (RuntimeException failure) {
            LOG.error("gateway_same_token_refresh_failed reason={}", failure.getClass().getName());
        }
    }
}
