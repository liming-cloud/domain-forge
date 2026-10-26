package org.domainforge.iam.infrastructure.security;

import org.domainforge.iam.application.port.authentication.SecretOperations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 使用JCA成熟随机、SHA-256与HMAC；密码只能通过Passwords处理。
 */
public final class JcaSecretOperations implements SecretOperations {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    /**
     * 指纹密钥必须来自部署输入，至少256位；无默认值且复制外部数组。
     */
    public JcaSecretOperations(byte[] fingerprintKey) {
        if (fingerprintKey == null || fingerprintKey.length < 32 || fingerprintKey.length > 64) {
            throw new IllegalArgumentException("指纹密钥必须为32至64字节");
        }
        key = new SecretKeySpec(fingerprintKey.clone(), "HmacSHA256");
    }

    @Override
    public String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 摘要输入有界；高熵秘密不可为空。
     */
    @Override
    public String digest(String secret) {
        if (secret == null || secret.length() > 4096 || secret.isEmpty())
            throw new IllegalArgumentException("秘密输入无效");
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("标准摘要算法不可用");
        }
    }

    /**
     * 逐字段加长度前缀，避免不同字段组合产生相同序列；不保存原始输入。
     */
    @Override
    public String fingerprint(String... fields) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            for (String field : fields) {
                if (field == null || field.length() > 4096) throw new IllegalArgumentException("命令指纹字段无效");
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                mac.update(value);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("标准HMAC算法不可用");
        }
    }
}
