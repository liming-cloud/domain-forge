package org.domainforge.iam.interfaces.security;

import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.Set;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;

/** 原生TLS已经验证证书链；此处进一步把客户端SAN身份绑定本项目的内部当前状态操作。 */
public final class ServiceCertificateIdentity {
  private final Set<String> allowed;

  /** 明确服务身份白名单，无默认跨项目共享或客户端Header覆盖。 */
  public ServiceCertificateIdentity(String projectId, Set<String> allowed) {
    if (projectId == null
        || !projectId.matches("[a-z][a-z0-9-]{2,63}")
        || allowed.isEmpty()
        || allowed.stream()
            .anyMatch(
                identity ->
                    !identity.matches("urn:domainforge:" + projectId + ":[a-z][a-z0-9-]{2,63}"))) {
      throw new IllegalArgumentException("内部服务身份必须明确绑定本项目");
    }
    this.allowed = Set.copyOf(allowed);
  }

  /** 只读取容器提供的验证后证书属性，不信任任何转发证书或主体Header。 */
  public void requireCurrentStateCaller(HttpServletRequest request) {
    if (!request.isSecure()) throw forbidden();
    Object attribute = request.getAttribute("jakarta.servlet.request.X509Certificate");
    if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0) throw forbidden();
    try {
      X509Certificate leaf = chain[0];
      leaf.checkValidity();
      var usages = leaf.getExtendedKeyUsage();
      if (usages == null || !usages.contains("1.3.6.1.5.5.7.3.2")) throw forbidden();
      var names = leaf.getSubjectAlternativeNames();
      if (names == null
          || names.stream()
              .noneMatch(
                  name -> Integer.valueOf(6).equals(name.get(0)) && allowed.contains(name.get(1))))
        throw forbidden();
    } catch (java.security.cert.CertificateException e) {
      throw forbidden();
    }
  }

  private static IamException forbidden() {
    return new IamException(IamFailure.FORBIDDEN, "调用服务身份无效");
  }
}
