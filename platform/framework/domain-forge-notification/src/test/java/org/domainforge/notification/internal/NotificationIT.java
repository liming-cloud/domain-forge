package org.domainforge.notification.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.domainforge.notification.api.NotificationUnavailableException;
import org.domainforge.notification.api.SmsMessage;
import org.domainforge.notification.api.SmsRejected;
import org.domainforge.notification.api.SmsSender;
import org.domainforge.notification.api.VerificationCodes;
import org.domainforge.notification.api.VerificationRejected;
import org.domainforge.notification.api.VerifyOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Redis 上的验证码合同，短信服务商由本地 HTTP 替身按阿里云 SendSms 的公开合同应答（独立验签）：
 * 发送与失败分类、签发与校验、单次有效、过期、错误次数上限、重发间隔与每天上限、短信未发出时撤销签发、
 * Redis 中不出现号码与验证码明文、Redis 不可用时 503 语义。
 */
class NotificationIT {
    private static final String SECRET = "it-access-key-secret";
    private static final List<Map<String, String>> SENT = new CopyOnWriteArrayList<>();
    private static final AtomicReference<Reply> REPLY = new AtomicReference<>();
    private static HttpServer aliyun;
    private static ConfigurableApplicationContext application;

    @BeforeAll
    static void start() throws IOException {
        assertNotNull(System.getenv("REDIS_PORT"), "需要真实 Redis 验证环境");
        aliyun = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        aliyun.createContext("/", NotificationIT::answer);
        aliyun.start();
        String purpose = "--domainforge.verification.purposes.";
        application = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--spring.data.redis.port=" + System.getenv("REDIS_PORT"),
                "--spring.data.redis.password=" + System.getenv("REDIS_PASSWORD"),
                "--domainforge.sms.enabled=true",
                "--domainforge.sms.aliyun.endpoint=http://127.0.0.1:" + aliyun.getAddress().getPort(),
                "--domainforge.sms.aliyun.access-key-id=it-access-key",
                "--domainforge.sms.aliyun.access-key-secret=" + SECRET,
                "--domainforge.sms.sign-name=领域工坊",
                "--domainforge.sms.templates.login-code=SMS_100001",
                "--domainforge.sms.request-timeout=PT1S",
                "--domainforge.verification.enabled=true",
                "--domainforge.verification.key=" + Base64.getEncoder().encodeToString(new byte[32]),
                purpose + "login.template=login-code",
                purpose + "limited.template=login-code",
                purpose + "limited.resend-interval=PT1S",
                purpose + "limited.daily-limit=2",
                purpose + "strict.template=login-code",
                purpose + "strict.max-attempts=3",
                purpose + "short.template=login-code",
                purpose + "short.ttl=PT1S");
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
        if (aliyun != null) aliyun.stop(0);
    }

    @BeforeEach
    void reset() {
        SENT.clear();
        REPLY.set(new Reply(200, "{\"Code\":\"OK\",\"Message\":\"OK\",\"BizId\":\"biz-1\",\"RequestId\":\"r\"}", 0));
        application.getBean(RedissonClient.class).getKeys().deleteByPattern("domainforge:verification:*");
    }

    /**
     * 阿里云替身：按 ACS3-HMAC-SHA256 独立验签，签名不符返回 400 SignatureDoesNotMatch；否则记录参数并按预设应答。
     */
    private static void answer(HttpExchange exchange) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(decode(parts[0]), decode(parts[1]));
        }
        var headers = new TreeMap<String, String>();
        headers.put("host", exchange.getRequestHeaders().getFirst("Host"));
        for (String name : List.of("x-acs-action", "x-acs-content-sha256", "x-acs-date", "x-acs-signature-nonce",
                "x-acs-version"))
            headers.put(name, exchange.getRequestHeaders().getFirst(name));
        Matcher authorization = Pattern.compile("ACS3-HMAC-SHA256 Credential=it-access-key,SignedHeaders=([^,]+),"
                + "Signature=([0-9a-f]{64})").matcher(exchange.getRequestHeaders().getFirst("Authorization"));
        boolean signed = authorization.matches() && authorization.group(1).equals(AcsSignature.signedHeaders(headers))
                && authorization.group(2).equals(AcsSignature.sign("POST", query, headers, "", SECRET))
                && "SendSms".equals(headers.get("x-acs-action")) && "2017-05-25".equals(headers.get("x-acs-version"));
        Reply reply = signed ? REPLY.get() : new Reply(400, "{\"Code\":\"SignatureDoesNotMatch\"}", 0);
        if (signed) SENT.add(query);
        try {
            Thread.sleep(reply.delayMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static void reply(int status, String code) {
        REPLY.set(new Reply(status, "{\"Code\":\"" + code + "\",\"Message\":\"x\",\"RequestId\":\"r\"}", 0));
    }

    private static SmsSender sms() {
        return application.getBean(SmsSender.class);
    }

    private static VerificationCodes codes() {
        return application.getBean(VerificationCodes.class);
    }

    private static String lastCode() {
        String param = SENT.getLast().get("TemplateParam");
        return param.replaceAll("\\{\"code\":\"([0-9]+)\"}", "$1");
    }

    // 发送：号码规范化、签名与模板参数；受理回执为 BizId。
    @Test
    void sendsSignedRequest() {
        var receipt = sms().send(new SmsMessage("+86 138-0013-8000", "login-code", Map.of("code", "123456")));
        assertEquals("biz-1", receipt.messageId());
        var query = SENT.getFirst();
        assertEquals("13800138000", query.get("PhoneNumbers"));
        assertEquals("领域工坊", query.get("SignName"));
        assertEquals("SMS_100001", query.get("TemplateCode"));
        assertEquals("{\"code\":\"123456\"}", query.get("TemplateParam"));
    }

    // 失败分类：号码无效与频控为业务拒绝；余额、签名等账号问题、5xx 与超时为不可用；本地判定无效的号码不调用服务商。
    @Test
    void failuresAreClassified() {
        var message = new SmsMessage("13800138000", "login-code", Map.of());
        reply(200, "isv.MOBILE_NUMBER_ILLEGAL");
        assertEquals(SmsRejected.Reason.INVALID_PHONE, assertThrows(SmsRejected.class, () -> sms().send(message))
                .reason());
        reply(200, "isv.BUSINESS_LIMIT_CONTROL");
        assertEquals(SmsRejected.Reason.RATE_LIMITED, assertThrows(SmsRejected.class, () -> sms().send(message))
                .reason());
        reply(200, "isv.AMOUNT_NOT_ENOUGH");
        assertThrows(NotificationUnavailableException.class, () -> sms().send(message));
        reply(500, "InternalError");
        assertThrows(NotificationUnavailableException.class, () -> sms().send(message));
        REPLY.set(new Reply(200, "{\"Code\":\"OK\"}", 1500));
        assertThrows(NotificationUnavailableException.class, () -> sms().send(message));
        int calls = SENT.size();
        assertThrows(SmsRejected.class, () -> sms().send(new SmsMessage("12345", "login-code", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> sms().send(new SmsMessage("13800138000", "x", Map.of())));
        assertEquals(calls, SENT.size());
    }

    // 签发并校验：错误为 MISMATCH，正确为 VERIFIED，成功后立即失效。
    @Test
    void codesAreSingleUse() {
        var issued = codes().issue("login", "13800138001");
        assertTrue(issued.expiresAt().isAfter(issued.resendAt()));
        String code = lastCode();
        assertEquals(6, code.length());
        String wrong = code.equals("000000") ? "000001" : "000000";
        assertEquals(VerifyOutcome.MISMATCH, codes().verify("login", "13800138001", wrong));
        assertEquals(VerifyOutcome.MISMATCH, codes().verify("login", "13800138001", "12ab"));
        assertEquals(VerifyOutcome.EXPIRED, codes().verify("strict", "13800138001", code), "用途之间互不通用");
        assertEquals(VerifyOutcome.VERIFIED, codes().verify("login", "+8613800138001", code));
        assertEquals(VerifyOutcome.EXPIRED, codes().verify("login", "13800138001", code));
        assertThrows(IllegalArgumentException.class, () -> codes().issue("undeclared", "13800138001"));
    }

    // 错误次数达到上限后作废，正确的验证码也不再通过。
    @Test
    void attemptsAreLimited() {
        codes().issue("strict", "13800138002");
        String code = lastCode();
        String wrong = code.equals("000000") ? "000001" : "000000";
        assertEquals(VerifyOutcome.MISMATCH, codes().verify("strict", "13800138002", wrong));
        assertEquals(VerifyOutcome.MISMATCH, codes().verify("strict", "13800138002", wrong));
        assertEquals(VerifyOutcome.LOCKED, codes().verify("strict", "13800138002", wrong));
        assertEquals(VerifyOutcome.LOCKED, codes().verify("strict", "13800138002", code));
    }

    // 过期后不能再校验。
    @Test
    void codesExpire() throws InterruptedException {
        codes().issue("short", "13800138003");
        String code = lastCode();
        Thread.sleep(1200);
        assertEquals(VerifyOutcome.EXPIRED, codes().verify("short", "13800138003", code));
    }

    // 重发间隔内拒绝；当天达到上限后拒绝；重发后旧验证码失效。
    @Test
    void resendIntervalAndDailyLimit() throws InterruptedException {
        codes().issue("limited", "13800138004");
        String first = lastCode();
        assertEquals(VerificationRejected.Reason.TOO_FREQUENT, assertThrows(VerificationRejected.class,
                () -> codes().issue("limited", "13800138004")).reason());
        Thread.sleep(1100);
        codes().issue("limited", "13800138004");
        String second = lastCode();
        if (!first.equals(second))
            assertEquals(VerifyOutcome.MISMATCH, codes().verify("limited", "13800138004", first));
        Thread.sleep(1100);
        assertEquals(VerificationRejected.Reason.DAILY_LIMIT, assertThrows(VerificationRejected.class,
                () -> codes().issue("limited", "13800138004")).reason());
        assertEquals(2, SENT.size());
    }

    // 短信没有发出时撤销签发：不占用重发间隔与当天次数。
    @Test
    void failedSendReleasesReservation() {
        reply(500, "InternalError");
        assertThrows(NotificationUnavailableException.class, () -> codes().issue("limited", "13800138005"));
        reply(200, "isv.MOBILE_NUMBER_ILLEGAL");
        assertThrows(SmsRejected.class, () -> codes().issue("limited", "13800138005"));
        REPLY.set(new Reply(200, "{\"Code\":\"OK\",\"BizId\":\"biz-2\"}", 0));
        codes().issue("limited", "13800138005");
        assertEquals(VerifyOutcome.VERIFIED, codes().verify("limited", "13800138005", lastCode()));
    }

    // Redis 中只有摘要：键与值都不含号码或验证码明文。
    @Test
    void redisHoldsNoSecrets() {
        codes().issue("login", "13800138006");
        String code = lastCode();
        var redis = application.getBean(RedissonClient.class);
        var keys = redis.getKeys().getKeysStreamByPattern("domainforge:verification:*").toList();
        assertEquals(3, keys.size(), keys.toString());
        for (String key : keys) {
            assertFalse(key.contains("13800138006"), key);
            if (key.endsWith(":code")) {
                var values = redis.<String, String>getMap(key, StringCodec.INSTANCE).readAllMap();
                assertFalse(values.get("digest").contains(code));
                assertEquals("0", values.get("attempts"));
            }
        }
    }

    // Redis 不可用：签发与校验都报告不可用（503 语义），不发送短信、不当作校验失败。
    @Test
    void redisOutageIsUnavailable() throws Exception {
        docker("pause");
        try {
            assertThrows(NotificationUnavailableException.class, () -> codes().issue("login", "13800138007"));
            assertThrows(NotificationUnavailableException.class,
                    () -> codes().verify("login", "13800138007", "123456"));
        } finally {
            docker("unpause");
        }
        assertTrue(SENT.isEmpty());
    }

    private static void docker(String command) throws Exception {
        var process = new ProcessBuilder("docker", command, System.getenv("REDIS_CONTAINER"))
                .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), "docker " + command + " 失败");
    }

    /**
     * 替身的一次应答。
     */
    record Reply(int status, String body, long delayMillis) {
    }

    /**
     * 自动配置（cache 组件建立 Redisson 客户端，通知组件按配置装配）。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
    }
}
