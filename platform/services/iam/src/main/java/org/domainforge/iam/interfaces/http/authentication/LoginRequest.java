package org.domainforge.iam.interfaces.http.authentication;

import io.swagger.v3.oas.annotations.media.Schema;

/** 登录输入；不接受客户端主体或权限。 */
@Schema(description = "登录输入；不接受客户端主体或权限")
public record LoginRequest(String loginName, String password) {
  /** 拒绝缺少命令必要字段；不会从空值推断写入意图。 */
  public LoginRequest {
    if (loginName == null || password == null) throw new IllegalArgumentException("请求字段不完整");
  }

  /** 禁止将敏感输入通过默认record字符串记录。 */
  @Override
  public String toString() {
    return "LoginRequest[redacted]";
  }
}
