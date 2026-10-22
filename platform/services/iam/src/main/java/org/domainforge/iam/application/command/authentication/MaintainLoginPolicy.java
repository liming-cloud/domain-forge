package org.domainforge.iam.application.command.authentication;

import org.domainforge.iam.application.port.authentication.LoginPolicyStatus;
import org.domainforge.iam.application.port.authentication.LoginProtection;
import org.springframework.stereotype.Service;

/** 本地运维用例要求明确旧策略与停登录声明，不提供运行期隐式策略覆盖。 */
@Service
public final class MaintainLoginPolicy {
  private final LoginProtection protection;

  /** 通过既有防护出口操作共享策略，不访问具体SQL或Redis。 */
  public MaintainLoginPolicy(LoginProtection protection) {
    this.protection = protection;
  }

  /** 查询只暴露策略摘要和恢复截止。 */
  public LoginPolicyStatus status() {
    return protection.policyStatus();
  }

  /** 操作者必须先停止全部登录并排空在途请求；声明不能替代实际停机。 */
  public LoginPolicyStatus apply(String expectedDigest, boolean loginStopped) {
    if (!loginStopped || expectedDigest == null || !expectedDigest.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("需要有效旧策略摘要及停止全部登录的明确确认");
    }
    return protection.changePolicy(expectedDigest);
  }
}
