package org.domainforge.iam.interfaces.http.protocol;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;

/** 同步JSON接口的有界内存输入，不将凭据写入文件或日志。 */
final class BodyInputStream extends ServletInputStream {
  private final ByteArrayInputStream input;

  BodyInputStream(byte[] bytes) {
    input = new ByteArrayInputStream(bytes);
  }

  @Override
  public int read() {
    return input.read();
  }

  @Override
  public int read(byte[] bytes, int offset, int length) {
    return input.read(bytes, offset, length);
  }

  @Override
  public boolean isFinished() {
    return input.available() == 0;
  }

  @Override
  public boolean isReady() {
    return true;
  }

  /** 当前同步合同不支持异步读取，不能伪报已登记回调。 */
  @Override
  public void setReadListener(ReadListener listener) {
    throw new IllegalStateException("此输入仅支持同步读取");
  }
}
