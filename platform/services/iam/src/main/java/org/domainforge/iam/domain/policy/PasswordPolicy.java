package org.domainforge.iam.domain.policy;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 密码可接受规则；只判断原始字符与阻断列表，不负责摘要算法或计算资源。
 */
public final class PasswordPolicy {
    private final Set<String> blocked;

    /**
     * 固定本次策略的阻断集合，外部修改不得改变运行中规则。
     */
    public PasswordPolicy(Set<String> blocked) {
        this.blocked = Set.copyOf(blocked);
    }

    /**
     * 注册和改密在相同格式规则上额外拒绝常见密码。
     */
    public void requireNew(String password) {
        requireInput(password);
        if (blocked.contains(password)) throw new IllegalArgumentException("密码过于常见");
    }

    /**
     * 登录也限制输入资源；不裁剪、规范化或截断Unicode与空白。
     */
    public void requireInput(String password) {
        if (password == null
                || password.codePointCount(0, password.length()) < 15
                || password.codePointCount(0, password.length()) > 128
                || password.getBytes(StandardCharsets.UTF_8).length > 512
                || password.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) {
            throw new IllegalArgumentException("密码须为15至128个Unicode码点且最多512字节");
        }
    }
}
