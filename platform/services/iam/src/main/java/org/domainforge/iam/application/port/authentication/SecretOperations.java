package org.domainforge.iam.application.port.authentication;

/** 高熵令牌和敏感命令指纹的技术出口，不自行定义密码协议。 */
public interface SecretOperations {
  /** 生成至少256位随机秘密，仅交付一次。 */
  String newSecret();

  /** 对高熵秘密计算固定摘要，不用于人类密码。 */
  String digest(String secret);

  /** 对有长度边界的字段序列使用部署密钥HMAC，不能泄露输入。 */
  String fingerprint(String... fields);
}
