package org.domainforge.access.backend.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 两个真实 JVM 的测试控制管道；令牌仅在进程内存中传递，不写入报告。 */
final class SessionProbeProcess implements AutoCloseable {
  private final Process process;
  private final BufferedWriter input;
  private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>(32);

  SessionProbeProcess(
      String schema, String application, String type, int port, Map<String, String> environment)
      throws Exception {
    var builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dlogback.configurationFile=session-probe/logback.xml",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            NativeSessionProbe.class.getName(),
            schema,
            application,
            type,
            Integer.toString(port));
    builder.environment().putAll(environment);
    builder.redirectErrorStream(true);
    process = builder.start();
    input =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    Thread.ofVirtual()
        .start(
            () -> {
              try (var reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                  if (line.equals("READY") || line.equals("LOCKED") || line.startsWith("RESULT "))
                    output.put(line);
                }
              } catch (Exception failure) {
                // 进程崩溃以 EOF 表示未知结果，不打印可能含凭据的框架异常。
              } finally {
                output.offer("EOF");
              }
            });
    try {
      assertTrue("READY".equals(next()), "隔离 JVM 启动未成功");
    } catch (Exception | AssertionError failure) {
      try {
        close();
      } catch (Exception | AssertionError cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  void send(String command) throws Exception {
    input.write(command);
    input.newLine();
    input.flush();
  }

  String call(String command) throws Exception {
    send(command);
    return next();
  }

  String next() throws InterruptedException {
    String line = output.poll(15, TimeUnit.SECONDS);
    assertNotNull(line, "隔离 JVM 未在本次验证窗口内返回");
    return line;
  }

  String login() throws Exception {
    String line = call("LOGIN");
    assertTrue(line.startsWith("RESULT OK "), "原生签发未成功");
    return line.substring("RESULT OK ".length());
  }

  @Override
  public void close() throws Exception {
    try {
      input.close();
    } catch (java.io.IOException ignored) {
      /* 崩溃进程的管道可能已经关闭。 */
    }
    if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
    assertTrue(process.waitFor(3, TimeUnit.SECONDS), "本次子进程未退出");
    output.clear();
  }
}
