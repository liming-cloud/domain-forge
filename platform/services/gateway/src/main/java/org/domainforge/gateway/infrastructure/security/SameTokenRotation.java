package org.domainforge.gateway.infrastructure.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.same.SaSameUtil;
import org.domainforge.cache.api.LockOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Same-Token 轮换：剩余有效期不足一半（或尚不存在）时生成新值，旧值作为“上一个值”在剩余有效期内仍被后端接受。
 *
 * <p>多个网关实例在同一把锁内检查剩余有效期，因此一次到期只轮换一次，不会连续轮换两次让持有旧值的请求被拒。
 * 启动后立即执行一次，保证接收流量前已有值；锁或 Redis 不可用时记录错误，下次再试。
 */
@Component
public class SameTokenRotation {
    private static final Logger LOG = LoggerFactory.getLogger(SameTokenRotation.class);
    private static final String LOCK = "gateway:same-token-rotation";

    private final LockOperations locks;

    /**
     * 跨实例互斥由 cache 组件的分布式锁提供。
     */
    public SameTokenRotation(LockOperations locks) {
        this.locks = locks;
    }

    /**
     * 是否需要轮换：值不存在，或剩余有效期不足总有效期的一半。
     *
     * @param remainingSeconds 剩余秒数；不存在为 -2，永不过期为 -1
     * @param timeoutSeconds   配置的有效期
     */
    static boolean due(long remainingSeconds, long timeoutSeconds) {
        if (remainingSeconds == -2) return true;
        if (remainingSeconds == -1) return false;
        return remainingSeconds < timeoutSeconds / 2;
    }

    /**
     * 定时检查；间隔远小于有效期的一半。
     */
    @Scheduled(initialDelay = 0, fixedDelayString = "${domainforge.gateway.same-token-check-interval:PT10M}")
    public void rotateIfDue() {
        try {
            locks.withLock(LOCK, Duration.ofSeconds(5), () -> {
                long remaining = SaManager.getSaSameTemplate().getTokenTimeout();
                if (!due(remaining, SaManager.getConfig().getSameTokenTimeout())) return;
                SaSameUtil.refreshToken();
                LOG.info("gateway_same_token_rotated");
            });
        } catch (RuntimeException failure) {
            LOG.error("gateway_same_token_rotation_failed reason={}", failure.getClass().getName());
        }
    }
}
