package org.domainforge.notification.internal;

import org.domainforge.notification.api.IssuedCode;
import org.domainforge.notification.api.NotificationUnavailableException;
import org.domainforge.notification.api.SmsMessage;
import org.domainforge.notification.api.SmsRejected;
import org.domainforge.notification.api.SmsSender;
import org.domainforge.notification.api.VerificationCodes;
import org.domainforge.notification.api.VerificationRejected;
import org.domainforge.notification.api.VerifyOutcome;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redis 中的验证码：只保存带密钥的摘要（号码在键中也只以摘要出现），签发、校验与计数都由 Lua 原子完成。
 *
 * <p>先在 Redis 中签发再发送短信；短信没有发出（拒绝或不可用）时撤销本次签发，不占用重发间隔与当天次数。
 */
public final class RedisVerificationCodes implements VerificationCodes {
    private static final Pattern PURPOSE = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String ISSUE = load("verification-issue.lua");
    private static final String RELEASE = load("verification-release.lua");
    private static final String VERIFY = load("verification-verify.lua");
    private final RedissonClient redis;
    private final SmsSender sms;
    private final Map<String, Policy> policies;
    private final byte[] key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    RedisVerificationCodes(RedissonClient redis, SmsSender sms, Map<String, Policy> policies, byte[] key,
                           Clock clock) {
        this.redis = redis;
        this.sms = sms;
        this.policies = Map.copyOf(policies);
        this.key = key.clone();
        this.clock = clock;
    }

    private static String load(String name) {
        try {
            return new ClassPathResource("domain-forge/notification/" + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    @Override
    public IssuedCode issue(String purpose, String phone) {
        Policy policy = policy(purpose);
        String number = PhoneNumbers.normalize(phone);
        String code = code(policy.length());
        String digest = digest(purpose, number, code);
        List<Object> keys = keys(purpose, number);
        Instant now = clock.instant();
        long result = eval(ISSUE, keys, digest, millis(policy.ttl()), millis(policy.resendInterval()),
                Integer.toString(policy.dailyLimit()), millis(Duration.ofHours(25)));
        if (result == -1)
            throw new VerificationRejected(VerificationRejected.Reason.TOO_FREQUENT, "发送过于频繁，请稍后再试");
        if (result == -2)
            throw new VerificationRejected(VerificationRejected.Reason.DAILY_LIMIT, "今天的发送次数已达上限");
        try {
            sms.send(new SmsMessage(number, policy.template(), Map.of(policy.parameter(), code)));
        } catch (SmsRejected | NotificationUnavailableException | IllegalArgumentException notSent) {
            eval(RELEASE, keys, digest);
            throw notSent;
        }
        return new IssuedCode(now.plus(policy.ttl()), now.plus(policy.resendInterval()));
    }

    @Override
    public VerifyOutcome verify(String purpose, String phone, String code) {
        Policy policy = policy(purpose);
        String number;
        try {
            number = PhoneNumbers.normalize(phone);
        } catch (SmsRejected invalid) {
            return VerifyOutcome.EXPIRED;
        }
        if (code == null || !code.matches("[0-9]{" + policy.length() + "}")) return VerifyOutcome.MISMATCH;
        long result = eval(VERIFY, keys(purpose, number).subList(0, 1), digest(purpose, number, code),
                Integer.toString(policy.maxAttempts()));
        return switch ((int) result) {
            case 1 -> VerifyOutcome.VERIFIED;
            case 2 -> VerifyOutcome.MISMATCH;
            case 3 -> VerifyOutcome.LOCKED;
            default -> VerifyOutcome.EXPIRED;
        };
    }

    private Policy policy(String purpose) {
        Policy policy = purpose == null || !PURPOSE.matcher(purpose).matches() ? null : policies.get(purpose);
        if (policy == null) throw new IllegalArgumentException("未声明的验证码用途：" + purpose);
        return policy;
    }

    private String code(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) code.append(random.nextInt(10));
        return code.toString();
    }

    /**
     * 键：同一用途与号码的三把键放在同一个哈希槽（{@code {...}}），号码只以摘要出现。
     */
    private List<Object> keys(String purpose, String number) {
        String slot = "domainforge:verification:{" + purpose + ":" + hmac("phone\n" + number).substring(0, 32) + "}";
        return List.of(slot + ":code", slot + ":resend", slot + ":day:" + DAY.format(clock.instant()
                .atZone(clock.getZone())));
    }

    private String digest(String purpose, String number, String code) {
        return hmac("code\n" + purpose + "\n" + number + "\n" + code);
    }

    private String hmac(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private long eval(String script, List<Object> keys, String... args) {
        try {
            Long result = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, script,
                    RScript.ReturnType.LONG, keys, (Object[]) args);
            return result;
        } catch (RuntimeException failure) {
            throw new NotificationUnavailableException("验证码存储不可用或结果未知", failure);
        }
    }

    private static String millis(Duration duration) {
        return Long.toString(duration.toMillis());
    }

    /**
     * 一种用途的验证码策略。
     *
     * @param template       短信逻辑模板名
     * @param parameter      模板中验证码变量名（默认 code）
     * @param length         位数（4—8，默认 6）
     * @param ttl            有效期（默认 5 分钟）
     * @param maxAttempts    最多校验次数（默认 5）
     * @param resendInterval 重发间隔（默认 60 秒）
     * @param dailyLimit     同一号码每天最多发送次数（默认 10）
     */
    record Policy(String template, String parameter, int length, Duration ttl, int maxAttempts,
                  Duration resendInterval, int dailyLimit) {
        Policy {
            if (template == null || template.isBlank()) throw new IllegalArgumentException("验证码用途须指定短信模板");
            if (length < 4 || length > 8 || maxAttempts < 1 || dailyLimit < 1 || ttl.isNegative() || ttl.isZero()
                    || resendInterval.isNegative())
                throw new IllegalArgumentException("验证码策略取值无效");
        }
    }
}
