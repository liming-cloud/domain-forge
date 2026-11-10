package org.domainforge.idempotency.internal;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Redis 中的幂等记录：开始、续租、保存结果、放弃都由 Lua 原子完成；Redis 故障时抛出异常，由调用方拒绝请求。
 */
class IdempotencyRecords {
    private static final String BEGIN = load("idempotency-begin.lua");
    private static final String RENEW = load("idempotency-renew.lua");
    private static final String COMPLETE = load("idempotency-complete.lua");
    private static final String RELEASE = load("idempotency-release.lua");
    private final RedissonClient redis;

    IdempotencyRecords(RedissonClient redis) {
        this.redis = redis;
    }

    private static String load(String name) {
        try {
            return new ClassPathResource("domain-forge/idempotency/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    /**
     * 开始处理：首次占用记录（处理租期内有效），或报告已有记录的状态。
     */
    Outcome begin(String key, String fingerprint, Duration lease) {
        List<Object> result = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, BEGIN,
                RScript.ReturnType.LIST, List.of(key), fingerprint, Long.toString(lease.toMillis()));
        return switch (String.valueOf(result.getFirst())) {
            case "new" -> new Outcome(Outcome.State.NEW, 0, null, null, null);
            case "mismatch" -> new Outcome(Outcome.State.MISMATCH, 0, null, null, null);
            case "processing" -> new Outcome(Outcome.State.PROCESSING, 0, null, null, null);
            default -> new Outcome(Outcome.State.DONE, Integer.parseInt(String.valueOf(result.get(1))),
                    String.valueOf(result.get(2)), Base64.getDecoder().decode(String.valueOf(result.get(3))),
                    String.valueOf(result.get(4)));
        };
    }

    /**
     * 处理期间续租：记录仍是本次处理时把租期重新设为 lease。
     */
    void renew(String key, String fingerprint, Duration lease) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, RENEW, RScript.ReturnType.LONG,
                List.of(key), fingerprint, Long.toString(lease.toMillis()));
    }

    /**
     * 保存首次结果。
     */
    void complete(String key, String fingerprint, int status, String type, byte[] body, String location,
            Duration ttl) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, COMPLETE, RScript.ReturnType.LONG,
                List.of(key), fingerprint, Integer.toString(status), type, Base64.getEncoder().encodeToString(body),
                Long.toString(ttl.toMillis()), location);
    }

    /**
     * 放弃本次处理，允许重试。
     */
    void release(String key, String fingerprint) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, RELEASE, RScript.ReturnType.LONG,
                List.of(key), fingerprint);
    }

    /**
     * 开始处理的结果。
     *
     * @param state    状态
     * @param status   已完成时的状态码
     * @param type     已完成时的内容类型（没有时为空串）
     * @param body     已完成时的响应体
     * @param location 已完成时的 Location 响应头（没有时为空串）
     */
    record Outcome(State state, int status, String type, byte[] body, String location) {
        /**
         * 记录状态。
         */
        enum State {
            NEW, MISMATCH, PROCESSING, DONE
        }
    }
}
