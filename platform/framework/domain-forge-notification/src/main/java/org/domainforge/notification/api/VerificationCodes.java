package org.domainforge.notification.api;

/**
 * 通用短信验证码：生成、发送、校验、过期与使用限制。用途（purpose）由业务定义并在配置中声明模板与限制，
 * 验证成功之后做什么由业务决定。验证码只以带密钥的摘要保存，校验成功即失效。
 */
public interface VerificationCodes {
    /**
     * 生成验证码并以短信发送。
     *
     * @param purpose 用途，须在 {@code domainforge.verification.purposes.<用途>} 中声明
     * @param phone   手机号
     * @return 过期与可重发时间
     * @throws VerificationRejected               距上次发送未满重发间隔，或当天发送已达上限
     * @throws SmsRejected                        号码无效或被服务商频控（不计入次数）
     * @throws NotificationUnavailableException 短信服务商或验证码存储不可用（不计入次数）
     */
    IssuedCode issue(String purpose, String phone);

    /**
     * 校验验证码；成功后立即失效，错误次数达到上限后该验证码作废。
     *
     * @throws NotificationUnavailableException 验证码存储不可用
     */
    VerifyOutcome verify(String purpose, String phone, String code);
}
