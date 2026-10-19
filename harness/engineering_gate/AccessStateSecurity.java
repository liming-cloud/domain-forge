package probe.access;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 合成登录及可信身份接入；Redis 原生原子计数不替代产品防护策略设计。 */
@Component
public class AccessStateSecurity implements StpInterface {
  private final AccessStateStore store;
  private final StringRedisTemplate redis;
  private final int limit;
  private final String project;

  /** 固定校准默认值允许合法调整，缺少内部配置仍可启用防护。 */
  public AccessStateSecurity(
      AccessStateStore store,
      StringRedisTemplate redis,
      @Value("${gate.login-limit:3}") int limit,
      @Value("${gate.project}") String project) {
    if (limit < 1 || limit > 5) {
      throw new IllegalArgumentException("limit");
    }
    this.store = store;
    this.redis = redis;
    this.limit = limit;
    this.project = project;
  }

  /** 在真实登录路径原子保留一次尝试，成功后清理，失败窗口自然恢复。 */
  public String login(long id, String password) {
    String key = project + ":fail:" + id;
    String script =
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],2000) end;"
            + " return n";
    Long attempts = redis.execute(new DefaultRedisScript<>(script, Long.class), List.of(key));
    if (attempts == null || attempts > limit) {
      throw new Limited();
    }
    AccessStateStore.Account account = store.account(id);
    if (!store.matches(account, password)) {
      throw new BadCredentials();
    }
    redis.delete(key);
    StpUtil.login(id);
    StpUtil.getTokenSession().set("revision", account.revision());
    return StpUtil.getTokenValue();
  }

  /** 每次校验当前账号和密码版本，跨实例旧会话不能等待自然过期。 */
  public AccessStateStore.Account current() {
    StpUtil.checkLogin();
    AccessStateStore.Account account = store.account(StpUtil.getLoginIdAsLong());
    Object revision = StpUtil.getTokenSession().get("revision");
    if (account == null
        || !account.active()
        || !Integer.valueOf(account.revision()).equals(revision)) {
      throw new BadCredentials();
    }
    return account;
  }

  /** 权限来自当次项目数据库，撤权后不复用登录时的集合。 */
  @Override
  public List<String> getPermissionList(Object id, String type) {
    AccessStateStore.Account account = store.account(Long.parseLong(id.toString()));
    return account == null ? List.of() : Arrays.asList(account.permissions().split(","));
  }

  /** 原生角色判断也从当前项目记录读取，不与操作权限混为同一集合。 */
  @Override
  public List<String> getRoleList(Object id, String type) {
    AccessStateStore.Account account = store.account(Long.parseLong(id.toString()));
    return account == null ? List.of() : Arrays.asList(account.roles().split(","));
  }

  /** 合成凭证拒绝只使用固定公开表达。 */
  public static class BadCredentials extends RuntimeException {}

  /** 固定窗口的过量尝试与凭证错误明确区分。 */
  public static class Limited extends RuntimeException {}
}
