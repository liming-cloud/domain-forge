package org.domainforge.iam.infrastructure.security;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.domain.policy.PasswordPolicy;
import org.domainforge.iam.infrastructure.configuration.LoginProtectionSettings;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** 固定Argon2id安全参数及有界工作池，不将用户密码送往外部服务。 */
public final class ArgonPasswords implements Passwords, AutoCloseable {
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  private final ThreadPoolExecutor workers;
  private final PasswordPolicy policy;
  private final String fakeDigest;
  private final int waitMillis;

  /** 按已校验的本实例资源边界建立工作池、假摘要及固定密码列表。 */
  public ArgonPasswords(LoginProtectionSettings settings) {
    waitMillis = settings.hashWaitMillis();
    workers =
        new ThreadPoolExecutor(
            settings.hashWorkers(),
            settings.hashWorkers(),
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(settings.hashQueueCapacity()),
            task -> {
              Thread thread = new Thread(task, "iam-password");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    try (var stream =
        ArgonPasswords.class.getResourceAsStream("/security/common-passwords-v1.txt")) {
      if (stream == null) throw new IllegalStateException("缺少固定密码阻断列表");
      policy =
          new PasswordPolicy(
              new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                  .lines()
                  .filter(line -> !line.startsWith("#"))
                  .collect(Collectors.toUnmodifiableSet()));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("密码阻断列表读取失败");
    }
    fakeDigest = encoder.encode("not-an-account-" + java.util.UUID.randomUUID());
  }

  /** 创建和改密共用策略；不裁剪、规范化或截断Unicode密码。 */
  @Override
  public String encode(String password) {
    try {
      policy.requireNew(password);
    } catch (IllegalArgumentException rejected) {
      throw new IamException(IamFailure.INVALID_REQUEST, rejected.getMessage());
    }
    return compute(() -> encoder.encode(password));
  }

  /** 未知账号同样执行真实Argon2匹配，调用方对结果统一返回认证失败。 */
  @Override
  public boolean matches(String password, String digest) {
    try {
      policy.requireInput(password);
    } catch (IllegalArgumentException rejected) {
      throw new IamException(IamFailure.INVALID_REQUEST, rejected.getMessage());
    }
    return compute(() -> encoder.matches(password, digest == null ? fakeDigest : digest))
        && digest != null;
  }

  /** 排队加计算不超过配置等待上限；超时取消结果且移除尚未执行任务，计算线程总数不增加。 */
  private <T> T compute(Callable<T> task) {
    Future<T> future;
    try {
      future = workers.submit(task);
    } catch (RejectedExecutionException e) {
      throw new IamException(IamFailure.RATE_LIMITED, "密码计算繁忙，请稍后重试");
    }
    try {
      return future.get(waitMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      workers.remove((Runnable) future);
      throw new IamException(IamFailure.UNAVAILABLE, "密码计算被中断");
    } catch (TimeoutException e) {
      future.cancel(true);
      workers.remove((Runnable) future);
      throw new IamException(IamFailure.RATE_LIMITED, "密码计算繁忙，请稍后重试");
    } catch (ExecutionException e) {
      throw new IamException(IamFailure.UNAVAILABLE, "密码计算不可用");
    }
  }

  /** 实际运行中的计算数，用于本实例资源观测而非准入依据。 */
  public int activeComputations() {
    return workers.getActiveCount();
  }

  /** 实际有界排队深度。 */
  public int queuedComputations() {
    return workers.getQueue().size();
  }

  /** 停止接受新密码任务并中断本实例工作池。 */
  @Override
  public void close() {
    workers.shutdownNow();
  }
}
