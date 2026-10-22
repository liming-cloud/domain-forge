package org.domainforge.iam.interfaces.cli;

import java.util.function.Supplier;
import org.domainforge.iam.application.command.authentication.MaintainLoginPolicy;
import org.domainforge.iam.application.port.authentication.LoginPolicyStatus;
import org.springframework.context.ConfigurableApplicationContext;

/** 登录策略维护只从本地非Web入口进入，不能伪装成业务HTTP配置修改。 */
public final class LoginPolicyCommand {
  private LoginPolicyCommand() {}

  /** 固定参数顺序避免未知或重复输入被静默忽略，控制台不输出任何凭据。 */
  public static void run(String[] args, Supplier<ConfigurableApplicationContext> start) {
    boolean status = args.length == 1 && "status".equals(args[0]);
    boolean apply =
        args.length == 3
            && "apply".equals(args[0])
            && args[1].matches("--expected-digest=[a-f0-9]{64}")
            && "--confirm-login-stopped".equals(args[2]);
    if (!status && !apply)
      throw new IllegalArgumentException(
          "使用login-policy status或apply --expected-digest=<摘要> --confirm-login-stopped");
    try (var context = start.get()) {
      var maintenance = context.getBean(MaintainLoginPolicy.class);
      LoginPolicyStatus result =
          status
              ? maintenance.status()
              : maintenance.apply(args[1].substring("--expected-digest=".length()), true);
      System.out.println(
          "login_policy currentDigest="
              + result.currentDigest()
              + " configuredDigest="
              + result.configuredDigest()
              + " blockedUntil="
              + result.blockedUntil());
    }
  }
}
