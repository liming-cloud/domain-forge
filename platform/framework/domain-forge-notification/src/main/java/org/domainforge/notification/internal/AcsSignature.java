package org.domainforge.notification.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 阿里云 OpenAPI V3 签名（ACS3-HMAC-SHA256）：规范化请求 → SHA-256 → HMAC-SHA256。以官方文档的示例值验证。
 */
final class AcsSignature {
    static final String ALGORITHM = "ACS3-HMAC-SHA256";
    static final String EMPTY_SHA256 = sha256Hex("");

    private AcsSignature() {
    }

    /**
     * 计算签名。
     *
     * @param method  HTTP 方法
     * @param query   查询参数（未编码）
     * @param headers 参与签名的请求头（名称小写）
     * @param body    请求体
     * @param secret  AccessKey Secret
     * @return 十六进制小写签名
     */
    static String sign(String method, Map<String, String> query, SortedMap<String, String> headers, String body,
                       String secret) {
        String canonical = method + "\n/\n" + canonicalQuery(query) + "\n"
                + headers.entrySet().stream().map(header -> header.getKey() + ":" + header.getValue().strip() + "\n")
                .collect(Collectors.joining())
                + "\n" + signedHeaders(headers) + "\n" + sha256Hex(body);
        return HexFormat.of().formatHex(hmac(secret, ALGORITHM + "\n" + sha256Hex(canonical)));
    }

    /**
     * 参与签名的请求头名称，按字典序以分号连接。
     */
    static String signedHeaders(SortedMap<String, String> headers) {
        return String.join(";", headers.keySet());
    }

    /**
     * 规范化查询串：按名称排序，名称与值分别按 RFC 3986 编码。
     */
    static String canonicalQuery(Map<String, String> query) {
        return new TreeMap<>(query).entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    /**
     * 只保留 {@code A-Z a-z 0-9 - _ . ~}，其余字节编码为大写 {@code %XX}。
     */
    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (Character.isLetterOrDigit(c) && c < 128 || c == '-' || c == '_' || c == '.' || c == '~')
                out.append((char) c);
            else
                out.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) c));
        }
        return out.toString();
    }

    static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] hmac(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
