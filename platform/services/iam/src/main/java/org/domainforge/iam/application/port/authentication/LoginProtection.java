package org.domainforge.iam.application.port.authentication;

/** 多实例共享登录防护及有界恢复，不用单机计数或临时断网后清零。 */
public interface LoginProtection {
  /** 仅首次受控初始化在同一数据库事务中建立防护代次。 */
  void initialize();

  /** 原子预占账号与直接来源地址额度，未知状态和恢复窗口拒绝。 */
  LoginReservation reserve(String normalizedName, String directAddress);

  /** 成功登录只移除本次账号预占，地址尝试次数继续保留。 */
  void succeeded(LoginReservation reservation);

  /** 仅供显式维护入口查看已初始化策略和本实例目标策略。 */
  LoginPolicyStatus policyStatus();

  /** 全部实例停登录后，核对预期旧摘要并受控切换到部署目标策略。 */
  LoginPolicyStatus changePolicy(String expectedDigest);
}
