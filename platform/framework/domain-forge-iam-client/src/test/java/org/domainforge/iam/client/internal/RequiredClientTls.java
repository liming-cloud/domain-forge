package org.domainforge.iam.client.internal;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import javax.net.ssl.SSLContext;

/** 本地协议反例使用真实双向TLS，不能靠信任所有证书绕过握手。 */
final class RequiredClientTls extends HttpsConfigurator {
  RequiredClientTls(SSLContext context) {
    super(context);
  }

  @Override
  public void configure(HttpsParameters parameters) {
    var ssl = getSSLContext().getDefaultSSLParameters();
    ssl.setNeedClientAuth(true);
    parameters.setSSLParameters(ssl);
  }
}
