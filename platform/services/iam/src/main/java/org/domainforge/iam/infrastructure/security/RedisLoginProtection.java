package org.domainforge.iam.infrastructure.security;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.LoginPolicyStatus;
import org.domainforge.iam.application.port.authentication.LoginProtection;
import org.domainforge.iam.application.port.authentication.LoginReservation;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.Transactions;
import org.domainforge.iam.infrastructure.configuration.LoginProtectionSettings;
import org.domainforge.iam.infrastructure.persistence.session.ProtectionMapper;
import org.domainforge.iam.infrastructure.persistence.session.ProtectionRow;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 单Redis主节点原子防护；SQL记录策略和代次，未知状态须经过完整计数窗口。 */
@Component
public final class RedisLoginProtection implements LoginProtection {
  private static final String GENERATION_KEY = "iam:protection:generation";
  private final StringRedisTemplate redis;
  private final ProtectionMapper mapper;
  private final Transactions transactions;
  private final SecretOperations secrets;
  private final LoginProtectionSettings settings;
  private final String policyDigest;
  private final DefaultRedisScript<Long> reserveScript;
  private final DefaultRedisScript<Long> successScript;

  /** 脚本由制品加载，不接收任意外部脚本或计数键；部署须为有界noeviction节点。 */
  public RedisLoginProtection(
      StringRedisTemplate redis,
      ProtectionMapper mapper,
      Transactions transactions,
      SecretOperations secrets,
      LoginProtectionSettings settings) {
    this.redis = redis;
    this.mapper = mapper;
    this.transactions = transactions;
    this.secrets = secrets;
    this.settings = settings;
    this.policyDigest = settings.digest();
    reserveScript = new DefaultRedisScript<>();
    reserveScript.setLocation(new ClassPathResource("security/reserve-login.lua"));
    reserveScript.setResultType(Long.class);
    successScript = new DefaultRedisScript<>();
    successScript.setLocation(new ClassPathResource("security/login-success.lua"));
    successScript.setResultType(Long.class);
  }

  /** 首代只允许在管理初始化事务内写入，缺少数据库状态或重复设置一律拒绝。 */
  @Override
  public void initialize() {
    String generation = generation();
    if (mapper.initialize(generation, policyDigest, settings) != 1) throw unavailable();
  }

  /** 维护查询不自动登记策略，也不改变当前恢复截止。 */
  @Override
  public LoginPolicyStatus policyStatus() {
    return transactions.execute(() -> status(initializedState()));
  }

  /** 摘要比较和窗口更新在同一行锁事务内完成，错误或重复请求不能缩短恢复期。 */
  @Override
  public LoginPolicyStatus changePolicy(String expectedDigest) {
    return transactions.execute(
        () -> {
          var state = initializedState();
          if (!state.policyDigest().equals(expectedDigest)
              || mapper.changePolicy(expectedDigest, policyDigest, settings) != 1) {
            throw new IamException(IamFailure.CONFLICT, "当前策略不匹配或目标策略未改变");
          }
          return status(mapper.lock());
        });
  }

  private ProtectionRow initializedState() {
    var state = mapper.lock();
    if (state == null || state.generation() == null) throw unavailable();
    return state;
  }

  private LoginPolicyStatus status(ProtectionRow state) {
    return new LoginPolicyStatus(state.policyDigest(), policyDigest, state.blockedUntil());
  }

  /** 账号名和来源地址只以HMAC进入Redis键；地址由直接TCP连接提供。 */
  @Override
  public LoginReservation reserve(String name, String address) {
    if (address == null || address.length() > 64)
      throw new IamException(IamFailure.INVALID_REQUEST, "来源地址无效");
    String generation = generation();
    boolean allowed =
        transactions.execute(
            () -> {
              var state = mapper.lock();
              if (state == null || state.generation() == null || !samePolicy(state)) return false;
              if (!generation.equals(state.generation())) {
                mapper.recover(generation, settings.recoverySeconds());
                return false;
              }
              return state.allowed();
            });
    if (!allowed) throw new IamException(IamFailure.UNAVAILABLE, "登录防护处于受控恢复窗口");
    String accountKey = "iam:login:account:" + secrets.fingerprint("account", name);
    String addressKey = "iam:login:address:" + secrets.fingerprint("address", address);
    String attempt = UUID.randomUUID().toString();
    try {
      Long result =
          redis.execute(
              reserveScript,
              List.of(GENERATION_KEY, accountKey, addressKey),
              generation,
              attempt,
              Integer.toString(settings.accountAttempts()),
              Integer.toString(settings.accountWindowSeconds()),
              Integer.toString(settings.addressAttempts()),
              Integer.toString(settings.addressWindowSeconds()));
      if (result == null || result < 0) throw unavailable();
      if (result > 0) throw new IamException(IamFailure.RATE_LIMITED, "登录尝试过多，请稍后重试", result);
      return new LoginReservation(accountKey, attempt, generation);
    } catch (IamException e) {
      throw e;
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  private boolean samePolicy(ProtectionRow state) {
    return policyDigest.equals(state.policyDigest())
        && settings.accountAttempts() == state.accountAttempts()
        && settings.accountWindowSeconds() == state.accountWindowSeconds()
        && settings.addressAttempts() == state.addressAttempts()
        && settings.addressWindowSeconds() == state.addressWindowSeconds();
  }

  /** 完成只删除自身账号预占；不清空账号的其他失败，也不减地址计数。 */
  @Override
  public void succeeded(LoginReservation reservation) {
    try {
      Long result =
          redis.execute(
              successScript,
              List.of(GENERATION_KEY, reservation.accountKey()),
              reservation.generation(),
              reservation.attemptId());
      if (result == null || result < 0) throw unavailable();
    } catch (IamException e) {
      throw e;
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  /** 真实run_id识别重启/旧快照，持久标记识别FLUSHALL；新标记不自动恢复SQL允许状态。 */
  private String generation() {
    try {
      String runId = currentRun();
      String marker = redis.opsForValue().get(GENERATION_KEY);
      if (marker == null) {
        redis.opsForValue().setIfAbsent(GENERATION_KEY, runId + ":" + UUID.randomUUID());
        marker = redis.opsForValue().get(GENERATION_KEY);
      }
      if (marker == null || !marker.matches("[a-f0-9]{40}:[a-f0-9-]{36}")) throw unavailable();
      // 恢复旧快照时marker中的旧run_id不能伪装成本次运行身份。
      return runId + ":" + marker.substring(marker.indexOf(':') + 1);
    } catch (IamException e) {
      throw e;
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  private String currentRun() {
    Properties server =
        redis.execute(
            (RedisCallback<Properties>) connection -> connection.serverCommands().info("server"));
    Properties config =
        redis.execute(
            (RedisCallback<Properties>)
                connection -> connection.serverCommands().getConfig("maxmemory*"));
    if (server == null
        || config == null
        || !"noeviction".equals(config.getProperty("maxmemory-policy"))
        || Long.parseLong(config.getProperty("maxmemory", "0")) <= 0) throw unavailable();
    String runId = server.getProperty("run_id");
    if (runId == null || !runId.matches("[a-f0-9]{40}")) throw unavailable();
    return runId;
  }

  private static IamException unavailable() {
    return new IamException(IamFailure.UNAVAILABLE, "登录防护存储暂不可用");
  }
}
