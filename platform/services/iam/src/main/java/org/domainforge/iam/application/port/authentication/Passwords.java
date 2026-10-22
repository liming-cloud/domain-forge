package org.domainforge.iam.application.port.authentication;

/** 密码策略与成熟算法适配；计算资源有界，调用方不在长事务内等待哈希。 */
public interface Passwords {
  /** 校验新密码策略后生成带随机盐的自描述摘要。 */
  String encode(String password);

  /** 对真实或固定假摘要执行实际匹配；空摘要走相同算法，不泄露账号存在性。 */
  boolean matches(String password, String digest);
}
