package org.domainforge.access.api;

import cn.dev33.satoken.same.SaSameTemplate;
import cn.dev33.satoken.same.SaSameUtil;

/**
 * 服务间调用携带的 Same-Token：证明请求来自网关或系统内服务，而不是绕过网关的直连。
 *
 * <p>值由 Sa-Token 保存在共用会话 Redis 中并轮换；调用方每次请求都读取当前值，不缓存。它不代表用户身份，
 * 用户令牌仍原样放在 Authorization 头中。
 */
public final class SameTokens {
    /**
     * 携带 Same-Token 的请求头。
     */
    public static final String HEADER = SaSameTemplate.SAME_TOKEN;

    /**
     * 静态入口不允许实例化。
     */
    private SameTokens() {
    }

    /**
     * 读取当前值，尚不存在时由 Sa-Token 生成并保存。
     *
     * @return 当前 Same-Token
     * @throws AccessException 会话 Redis 不可用时为 UNAVAILABLE，调用方不得省略请求头继续调用
     */
    public static String current() {
        try {
            return SaSameUtil.getToken();
        } catch (RuntimeException failure) {
            throw new AccessException(AccessFailure.UNAVAILABLE);
        }
    }
}
