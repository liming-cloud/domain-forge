package org.domainforge.iam.interfaces.http.protocol;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 已在入口限制到16KiB的JSON请求，原始凭据不进入记录系统。 */
final class BoundedJsonRequest extends HttpServletRequestWrapper {
  private final BodyInputStream body;

  BoundedJsonRequest(HttpServletRequest request, byte[] bytes) {
    super(request);
    body = new BodyInputStream(bytes);
  }

  @Override
  public ServletInputStream getInputStream() {
    return body;
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
  }
}
