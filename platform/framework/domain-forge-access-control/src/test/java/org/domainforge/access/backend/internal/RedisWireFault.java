package org.domainforge.access.backend.internal;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 测试专用单次链路故障：转发原始字节，不生成或修改 Redis 命令／会话内容。 */
final class RedisWireFault implements AutoCloseable {
  /** 私有标准管道供其他模块的隔离测试复用；不增加产品端点或改变故障帧。 */
  public static void main(String[] arguments) throws Exception {
    try (var fault = new RedisWireFault(Integer.parseInt(arguments[0]));
        var input =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      System.out.println("READY " + fault.port());
      for (String line; (line = input.readLine()) != null; ) {
        String[] fields = line.split(" ", 4);
        switch (fields[0]) {
          case "ARM" -> fault.arm(fields[1], fields[3], Mode.valueOf(fields[2]));
          case "CAPTURED" -> {
            System.out.println("RESULT " + fault.awaitCaptured());
            continue;
          }
          case "RELEASE" -> fault.release();
          default -> throw new IllegalArgumentException("未知测试控制命令");
        }
        System.out.println("RESULT OK");
      }
    }
  }

  enum Mode {
    HOLD_WRITE,
    DROP_REPLY
  }

  private record Target(String command, String key, Mode mode) {}

  private record Frame(byte[] bytes, List<String> arguments) {}

  private final ServerSocket listener;
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private final AtomicReference<Target> target = new AtomicReference<>();
  private final CountDownLatch captured = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private volatile boolean closed;

  RedisWireFault(int upstreamPort) throws IOException {
    listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    Thread.ofVirtual().start(() -> accept(upstreamPort));
  }

  int port() {
    return listener.getLocalPort();
  }

  void arm(String command, String key, Mode mode) {
    target.set(new Target(command, key, mode));
  }

  boolean awaitCaptured() throws InterruptedException {
    return captured.await(10, TimeUnit.SECONDS);
  }

  void release() {
    release.countDown();
  }

  private void accept(int port) {
    try {
      while (!closed) {
        Socket downstream = listener.accept();
        Socket upstream = new Socket(InetAddress.getLoopbackAddress(), port);
        sockets.add(downstream);
        sockets.add(upstream);
        var drop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread.ofVirtual().start(() -> responses(upstream, downstream, drop));
        Thread.ofVirtual().start(() -> requests(downstream, upstream, drop));
      }
    } catch (IOException failure) {
      if (!closed) throw new IllegalStateException("测试转发入口失败", failure);
    }
  }

  private void requests(
      Socket downstream, Socket upstream, java.util.concurrent.atomic.AtomicBoolean drop) {
    try {
      var input = downstream.getInputStream();
      var output = upstream.getOutputStream();
      for (Frame frame; !closed && (frame = frame(input)) != null; ) {
        var selected = capture(frame);
        if (selected != null) {
          if (selected.mode() == Mode.HOLD_WRITE) {
            captured.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("延迟释放超时");
          } else {
            drop.set(true);
          }
          output.write(frame.bytes());
          output.flush();
          captured.countDown();
        } else {
          output.write(frame.bytes());
          output.flush();
        }
      }
    } catch (IOException | InterruptedException failure) {
      // 调用方超时／断连是被观察的故障，不将含原始帧的异常写入测试日志。
    }
  }

  private Target capture(Frame frame) {
    var selected = target.get();
    if (selected != null
        && frame.arguments().size() >= 2
        && selected.command().equalsIgnoreCase(frame.arguments().get(0))
        && selected.key().equals(frame.arguments().get(1))
        && target.compareAndSet(selected, null)) return selected;
    return null;
  }

  private void responses(
      Socket upstream, Socket downstream, java.util.concurrent.atomic.AtomicBoolean drop) {
    try {
      byte[] buffer = new byte[8192];
      for (int size; (size = upstream.getInputStream().read(buffer)) != -1; ) {
        if (!drop.get()) {
          downstream.getOutputStream().write(buffer, 0, size);
          downstream.getOutputStream().flush();
        }
      }
    } catch (IOException failure) {
      // 原始响应只转发或丢弃，不进入日志。
    }
  }

  /** RESP2/3 请求都是 bulk 数组；响应不解析，帧最多 128 个有限参数。 */
  private static Frame frame(InputStream input) throws IOException {
    int first = input.read();
    if (first == -1) return null;
    if (first != '*') throw new IOException("预期原生 Redis 数组请求");
    var bytes = new ByteArrayOutputStream();
    bytes.write(first);
    int count = number(input, bytes);
    if (count < 1 || count > 128) throw new IOException("测试帧参数超限");
    var arguments = new ArrayList<String>();
    for (int index = 0; index < count; index++) {
      int marker = input.read();
      if (marker != '$') throw new IOException("预期 bulk 参数");
      bytes.write(marker);
      int length = number(input, bytes);
      if (length < 0 || length > 65536) throw new IOException("测试帧大小超限");
      byte[] value = input.readNBytes(length + 2);
      if (value.length != length + 2) throw new EOFException();
      bytes.write(value);
      arguments.add(new String(value, 0, length, StandardCharsets.UTF_8));
    }
    return new Frame(bytes.toByteArray(), arguments);
  }

  private static int number(InputStream input, ByteArrayOutputStream bytes) throws IOException {
    var value = new StringBuilder();
    for (int index = 0; index < 12; index++) {
      int next = input.read();
      if (next == -1) throw new EOFException();
      bytes.write(next);
      if (next == '\r') {
        if (input.read() != '\n') throw new IOException("无效换行");
        bytes.write('\n');
        return Integer.parseInt(value.toString());
      }
      value.append((char) next);
    }
    throw new IOException("测试帧长度无效");
  }

  @Override
  public void close() throws IOException {
    closed = true;
    release();
    listener.close();
    for (var socket : sockets) socket.close();
  }
}
