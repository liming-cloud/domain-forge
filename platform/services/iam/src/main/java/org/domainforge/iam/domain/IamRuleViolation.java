package org.domainforge.iam.domain;

import org.domainforge.shared.BusinessRuleViolation;

/**
 * IAM 领域规则违反：带稳定错误码，提示是固定说明，可以对外返回。
 *
 * <p>输入格式或取值无效为 400，当前状态不允许为 409；由 web-support 统一转为 Problem Details。
 */
public final class IamRuleViolation extends BusinessRuleViolation {
    private IamRuleViolation(Kind kind, String code, String message) {
        super(kind, code, message);
    }

    /**
     * 输入格式或取值无效。
     *
     * @param code    稳定错误码
     * @param message 固定说明
     * @return 400 类规则违反
     */
    public static IamRuleViolation invalid(String code, String message) {
        return new IamRuleViolation(Kind.INVALID_REQUEST, code, message);
    }

    /**
     * 当前状态不允许该操作。
     *
     * @param code    稳定错误码
     * @param message 固定说明
     * @return 409 类规则违反
     */
    public static IamRuleViolation conflict(String code, String message) {
        return new IamRuleViolation(Kind.CONFLICT, code, message);
    }
}
