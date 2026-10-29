package org.domainforge.idempotency.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 幂等请求：带 {@code Idempotency-Key} 的已登录请求命中幂等入口时，按“账号＋方法＋路径＋键”占用 Redis 记录，
 * 以“方法＋路径＋请求体”的摘要识别同一请求；首次结果（非 5xx）保存后重复请求原样返回。
 *
 * <p>Redis 不可用时拒绝（503），不退化为不做幂等，避免重复执行。
 */
public final class IdempotencyFilter extends OncePerRequestFilter {
    static final String HEADER = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";
    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7e]{1,255}");
    private final IdempotentEndpoints endpoints;
    private final IdempotencyRecords records;
    private final Supplier<Object> accounts;
    private final Settings settings;

    IdempotencyFilter(IdempotentEndpoints endpoints, IdempotencyRecords records, Supplier<Object> accounts,
                      Settings settings) {
        this.endpoints = endpoints;
        this.records = records;
        this.accounts = accounts;
        this.settings = settings;
    }

    private static String sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(part);
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(utf8("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"data\":null}"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (key == null || !endpoints.matches(request.getMethod(), path)) {
            chain.doFilter(request, response);
            return;
        }
        if (!KEY.matcher(key).matches()) {
            reject(response, 400, "INVALID_REQUEST", "请求不合法");
            return;
        }
        Object account = accounts.get();
        if (account == null) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = body(request.getInputStream());
        if (body == null) {
            reject(response, 413, "PAYLOAD_TOO_LARGE", "请求内容过大");
            return;
        }
        String fingerprint = sha256(utf8(request.getMethod()), utf8(path), utf8(String.valueOf(
                request.getQueryString())), body);
        String record = "domainforge:idempotency:" + sha256(utf8(settings.application()),
                utf8(String.valueOf(account)), utf8(request.getMethod()), utf8(path), utf8(key));
        handle(new CachedBodyRequest(request, body), response, chain, record, fingerprint);
    }

    private void handle(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String record,
                        String fingerprint) throws ServletException, IOException {
        IdempotencyRecords.Outcome outcome;
        try {
            outcome = records.begin(record, fingerprint, settings.lease());
        } catch (RuntimeException unavailable) {
            LOGGER.error("idempotency_unavailable reason={}", unavailable.getClass().getName());
            reject(response, 503, "SERVICE_UNAVAILABLE", "服务暂不可用");
            return;
        }
        switch (outcome.state()) {
            case MISMATCH -> reject(response, 422, "IDEMPOTENCY_KEY_REUSED", "同一幂等键已用于不同的请求");
            case PROCESSING -> reject(response, 409, "IDEMPOTENCY_IN_PROGRESS", "相同的请求仍在处理中");
            case DONE -> replay(response, outcome);
            case NEW -> first(request, response, chain, record, fingerprint);
        }
    }

    private void first(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String record,
                       String fingerprint) throws ServletException, IOException {
        var captured = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, captured);
        } catch (IOException | ServletException | RuntimeException failure) {
            quietly(() -> records.release(record, fingerprint));
            throw failure;
        }
        byte[] out = captured.getContentAsByteArray();
        if (captured.getStatus() >= 500 || out.length > settings.maxResponseBytes())
            quietly(() -> records.release(record, fingerprint));
        else
            quietly(() -> records.complete(record, fingerprint, captured.getStatus(),
                    String.valueOf(captured.getContentType()), out, settings.ttl()));
        captured.copyBodyToResponse();
    }

    private static void replay(HttpServletResponse response, IdempotencyRecords.Outcome outcome) throws IOException {
        response.setStatus(outcome.status());
        if (!outcome.type().isEmpty() && !outcome.type().equals("null")) response.setContentType(outcome.type());
        response.setHeader(REPLAYED, "true");
        response.getOutputStream().write(outcome.body());
    }

    /**
     * 保存或放弃失败只影响之后的重复请求（记录在租期后过期），不改变本次已产生的结果。
     */
    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOGGER.error("idempotency_record_failed reason={}", failure.getClass().getName());
        }
    }

    private byte[] body(InputStream in) throws IOException {
        byte[] read = in.readNBytes(settings.maxRequestBytes() + 1);
        return read.length > settings.maxRequestBytes() ? null : read;
    }

    /**
     * 组件设置。
     *
     * @param application      服务名，记录键的一部分
     * @param ttl              首次结果的保存时长
     * @param lease            处理租期：首次请求在此期间未结束（如进程中断）时记录过期，可以重试
     * @param maxRequestBytes  参与幂等的请求体上限
     * @param maxResponseBytes 保存的响应体上限，超过时不保存
     */
    record Settings(String application, Duration ttl, Duration lease, int maxRequestBytes, int maxResponseBytes) {
    }
}
