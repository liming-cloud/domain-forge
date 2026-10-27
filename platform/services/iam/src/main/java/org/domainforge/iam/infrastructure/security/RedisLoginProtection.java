package org.domainforge.iam.infrastructure.security;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.LoginReservation;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.config.api.DynamicSettings;
import org.domainforge.iam.application.port.authentication.LoginProtection;
import org.domainforge.iam.infrastructure.configuration.LoginProtectionSettings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Redis 原子登录防护：Lua 脚本在一次原子操作内清理过期尝试、判断额度并预占。
 *
 * <p>Redis 由 cache 组件统一装配（Redisson），本类只在基础设施中使用其 Spring Data 连接。
 * 计数键必须不被淘汰：每次预占前确认 maxmemory-policy 为 noeviction 且设置了 maxmemory，否则拒绝登录。
 */
@Component
public final class RedisLoginProtection implements LoginProtection {
    private final StringRedisTemplate redis;
    private final SecretOperations secrets;
    private final DynamicSettings<LoginProtectionSettings> settings;
    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> successScript;

    /**
     * 脚本由制品加载，不接收任意外部脚本或计数键。
     */
    public RedisLoginProtection(
            StringRedisTemplate redis, SecretOperations secrets, DynamicSettings<LoginProtectionSettings> settings) {
        this.redis = redis;
        this.secrets = secrets;
        this.settings = settings;
        reserveScript = script("security/reserve-login.lua");
        successScript = script("security/login-success.lua");
    }

    private static DefaultRedisScript<Long> script(String location) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }

    private static IamException unavailable() {
        return new IamException(IamFailure.UNAVAILABLE, "登录防护存储暂不可用");
    }

    /**
     * 账号名和来源地址只以 HMAC 进入 Redis 键；地址由直接 TCP 连接提供。
     */
    @Override
    public LoginReservation reserve(String name, String address) {
        if (address == null || address.length() > 64)
            throw new IamException(IamFailure.INVALID_REQUEST, "来源地址无效");
        String accountKey = "iam:login:account:" + secrets.fingerprint("account", name);
        String addressKey = "iam:login:address:" + secrets.fingerprint("address", address);
        String attempt = UUID.randomUUID().toString();
        // 每次预占读取一次当前阈值，同一次判断内的次数与窗口来自同一份配置。
        LoginProtectionSettings limits = settings.current();
        Long result;
        try {
            requireNoEviction();
            result = redis.execute(
                    reserveScript,
                    List.of(accountKey, addressKey),
                    attempt,
                    Integer.toString(limits.accountAttempts()),
                    Integer.toString(limits.accountWindowSeconds()),
                    Integer.toString(limits.addressAttempts()),
                    Integer.toString(limits.addressWindowSeconds()));
        } catch (IamException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        }
        if (result == null || result < 0) throw unavailable();
        if (result > 0) throw new IamException(IamFailure.RATE_LIMITED, "登录尝试过多，请稍后重试", result);
        return new LoginReservation(accountKey, attempt);
    }

    /**
     * 只删除本次账号预占；不清空账号的其他失败，也不减地址计数。
     */
    @Override
    public void succeeded(LoginReservation reservation) {
        try {
            Long result = redis.execute(
                    successScript, List.of(reservation.accountKey()), reservation.attemptId());
            if (result == null || result < 0) throw unavailable();
        } catch (IamException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    /**
     * 防护计数被淘汰就等于失去限制，因此 Redis 必须是有内存上限的 noeviction 节点。
     */
    private void requireNoEviction() {
        Properties config = redis.execute(
                (RedisCallback<Properties>) connection -> connection.serverCommands().getConfig("maxmemory*"));
        if (config == null
                || !"noeviction".equals(config.getProperty("maxmemory-policy"))
                || Long.parseLong(config.getProperty("maxmemory", "0")) <= 0) throw unavailable();
    }
}
