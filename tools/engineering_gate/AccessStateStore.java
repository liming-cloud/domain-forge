package probe.access;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 固定合成账号与安全写入的真实 PostgreSQL 夹具，不作为产品仓储。 */
@Component
public class AccessStateStore {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final TransactionTemplate transactions;
  private final String project;

  /** 使用应用自己的连接、成熟密码实现与事务管理器。 */
  public AccessStateStore(
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      org.springframework.transaction.PlatformTransactionManager manager,
      @Value("${gate.project}") String project) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.transactions = new TransactionTemplate(manager);
    this.project = project;
  }

  /** 合成账号视图保留私有摘要，仅在内部校验，不返回 HTTP。 */
  public record Account(
      long id,
      boolean active,
      String permissions,
      int scope,
      int revision,
      String hash,
      String roles) {}

  /** 只初始化本轮空库的已知合成账号，重启不覆盖已提交状态。 */
  public void seed() {
    for (int id = 1; id <= 3; id++) {
      String permission = id == 1 ? "admin,read,write" : id == 2 ? "read,write" : "read";
      jdbc.update(
          "INSERT INTO gate_account(project,id,hash,active,permissions,scope,revision,roles)"
              + " VALUES(?,?,?,true,?,10,0,?) ON CONFLICT DO NOTHING",
          project,
          id,
          passwords.encode("Initial-gate-password!7"),
          permission,
          id == 1 ? "manager" : "reader");
    }
    for (int id : List.of(11, 12, 21)) {
      jdbc.update(
          "INSERT INTO gate_resource(project,id,owner,department,value) VALUES(?,?,?,?,?) ON"
              + " CONFLICT DO NOTHING",
          project,
          id,
          id == 12 ? 3 : 2,
          id == 21 ? 20 : 10,
          "seed-" + id);
    }
  }

  /** 每次从实际项目数据取得账号，不用演示权限集合代替查询。 */
  public Account account(long id) {
    List<Account> rows =
        jdbc.query(
            "SELECT id,active,permissions,scope,revision,hash,roles FROM gate_account WHERE"
                + " project=? AND id=?",
            (row, index) ->
                new Account(
                    row.getLong(1),
                    row.getBoolean(2),
                    row.getString(3),
                    row.getInt(4),
                    row.getInt(5),
                    row.getString(6),
                    row.getString(7)),
            project,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /** 成熟密码比较只在可信账号记录上进行。 */
  public boolean matches(Account account, String password) {
    return account != null && account.active() && passwords.matches(password, account.hash());
  }

  /** 固定合成资源同时受归属与部门范围限制。 */
  public Map<String, Object> resource(long id, Account actor) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id,value FROM gate_resource WHERE project=? AND id=? AND owner=? AND"
                + " department=?",
            project,
            id,
            actor.id(),
            actor.scope());
    if (rows.isEmpty()) {
      throw new cn.dev33.satoken.exception.NotPermissionException("resource");
    }
    return rows.getFirst();
  }

  /** 列表和单资源使用相同的可信范围，不接受调用方提供主体或部门。 */
  public List<Long> resources(Account actor) {
    return jdbc.queryForList(
        "SELECT id FROM gate_resource WHERE project=? AND owner=? AND department=? ORDER BY id"
            + " LIMIT 10",
        Long.class,
        project,
        actor.id(),
        actor.scope());
  }

  /** 实际条件写入必须影响一行，拒绝请求不能改变范围外数据。 */
  public void write(long id, Account actor, String value) {
    resource(id, actor);
    int count =
        jdbc.update(
            "UPDATE gate_resource SET value=? WHERE project=? AND id=? AND owner=? AND"
                + " department=?",
            value,
            project,
            id,
            actor.id(),
            actor.scope());
    if (count != 1) {
      throw new cn.dev33.satoken.exception.NotPermissionException("resource");
    }
  }

  /** 变更成功先以独立事务提交；审计失败不能回滚已生效结果。 */
  public Map<String, Object> change(long actor, long subject, String field, String value) {
    transactions.executeWithoutResult(status -> update(subject, field, value));
    return audit(actor, subject, field, "changed", false);
  }

  /** 合成状态字段由固定分支选取，不提供任意 SQL 配置。 */
  private void update(long subject, String field, String value) {
    int count =
        switch (field) {
          case "active" ->
              jdbc.update(
                  "UPDATE gate_account SET active=? WHERE project=? AND id=?",
                  Boolean.valueOf(value),
                  project,
                  subject);
          case "permissions" ->
              jdbc.update(
                  "UPDATE gate_account SET permissions=? WHERE project=? AND id=?",
                  value,
                  project,
                  subject);
          case "roles" ->
              jdbc.update(
                  "UPDATE gate_account SET roles=? WHERE project=? AND id=?",
                  value,
                  project,
                  subject);
          case "scope" ->
              jdbc.update(
                  "UPDATE gate_account SET scope=? WHERE project=? AND id=?",
                  Integer.valueOf(value),
                  project,
                  subject);
          default -> throw new IllegalArgumentException("field");
        };
    if (count != 1) {
      throw new IllegalArgumentException("subject");
    }
  }

  /** 密码和版本先提交，后续撤销失败必须保持未完成表达。 */
  public void password(Account actor, String oldPassword, String newPassword) {
    if (!matches(actor, oldPassword)) {
      throw new cn.dev33.satoken.exception.NotPermissionException("password");
    }
    String encoded = passwords.encode(newPassword);
    transactions.executeWithoutResult(
        status -> {
          int count =
              jdbc.update(
                  "UPDATE gate_account SET hash=?,revision=revision+1 WHERE project=? AND id=? AND"
                      + " revision=?",
                  encoded,
                  project,
                  actor.id(),
                  actor.revision());
          if (count != 1) {
            throw new IllegalStateException("concurrent change");
          }
        });
  }

  /** 审计仅保存可信主体与必要事实，不复制密码、令牌或摘要。 */
  public Map<String, Object> audit(
      long actor, long subject, String action, String result, boolean partial) {
    String event = UUID.randomUUID().toString();
    try {
      jdbc.update(
          "INSERT INTO gate_audit(event,project,actor,subject,action,result,partial,source)"
              + " VALUES(?::uuid,?,?,?,?,?,?,?)",
          event,
          project,
          actor,
          subject,
          action,
          result,
          partial,
          partial ? "current-database" : "completed-operation");
      return Map.of("business", "complete", "audit", "saved", "event", event);
    } catch (DataAccessException failure) {
      System.err.println("GATE_AUDIT_FAILED:" + event);
      return Map.of("business", "complete", "audit", "missing", "event", event);
    }
  }

  /** 人工补录仅声明当前可核对状态，原操作者和原时间缺失时不重建历史。 */
  public Map<String, Object> reconcile(long actor, long subject) {
    Account current = account(subject);
    if (current == null) {
      throw new IllegalArgumentException("subject");
    }
    String result =
        "active="
            + current.active()
            + ";scope="
            + current.scope()
            + ";revision="
            + current.revision();
    return audit(actor, subject, "manual-snapshot", result, true);
  }
}
