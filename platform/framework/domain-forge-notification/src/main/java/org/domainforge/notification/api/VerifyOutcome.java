package org.domainforge.notification.api;

/**
 * 校验结果。业务决定如何向用户呈现；不应区分“不存在”与“已过期”以外的更多信息。
 */
public enum VerifyOutcome {
    /** 正确，验证码已失效。 */
    VERIFIED,
    /** 不正确，仍可再试。 */
    MISMATCH,
    /** 不存在、已过期或已使用。 */
    EXPIRED,
    /** 错误次数已达上限，验证码作废，需要重新获取。 */
    LOCKED
}
