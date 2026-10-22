package org.domainforge.iam.application.command.registration;

import org.domainforge.iam.application.port.operation.CommandResult;

/** 邀请秘密仅首次返回；重放收据时secret为空，调用方须显式重发。 */
public record InvitationIssued(CommandResult result, String secret) {
  /** 防止默认record字符串将一次性秘密写入日志。 */
  @Override
  public String toString() {
    return "InvitationIssued[result=" + result + "]";
  }
}
