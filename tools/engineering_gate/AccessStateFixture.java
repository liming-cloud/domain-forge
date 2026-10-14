package probe.access;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import java.util.Map;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 隔离访问合同夹具，全部账号和接口仅用于第五层校准。 */
@SpringBootApplication
public class AccessStateFixture {
  /** 复用已经校准的成熟实现与合成工作参数，不新增产品默认参数。 */
  @Bean
  PasswordEncoder passwords() {
    return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  }

  /** 合成账号初始化属于夹具，观察器不代替应用写入或登录。 */
  @Bean
  ApplicationRunner fixtureSeed(AccessStateStore store) {
    return args -> store.seed();
  }

  /** 统一接入原生注解与当前状态，未声明入口不获得默认许可。 */
  @Configuration
  static class AccessConfiguration implements WebMvcConfigurer {
    private final AccessStateSecurity security;

    /** 绑定实际应用的身份检查。 */
    AccessConfiguration(AccessStateSecurity security) {
      this.security = security;
    }

    /** 匿名由原生 SaIgnore 处理，其余业务入口继续核对当前账号。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
      registry
          .addInterceptor(
              new SaInterceptor(
                  handler -> {
                    HandlerMethod target = (HandlerMethod) handler;
                    boolean declared =
                        target.hasMethodAnnotation(SaCheckLogin.class)
                            || target.hasMethodAnnotation(SaCheckPermission.class)
                            || target.getBeanType().isAnnotationPresent(SaCheckLogin.class);
                    if (!declared) {
                      throw new NotPermissionException("undeclared");
                    }
                    security.current();
                  }))
          .addPathPatterns("/gate/**");
    }
  }

  /** 合成 JSON 外壳保留固定字段，不公开底层异常。 */
  public record Envelope(String code, String message, Object data) {}

  /** 固定请求只承载数据输入，actor 等客户端字段不能成为授权事实。 */
  public record Login(long id, String password) {}

  /** 合成管理写入只接受固定字段，actor 专用于验证不可信输入不能冒充主体。 */
  public record Change(long id, String field, String value, long actor) {}

  /** 合成密码变更不向外返回旧密码、新密码或保存摘要。 */
  public record PasswordChange(String oldPassword, String newPassword) {}

  /** 业务路径、权限和资源检查均由被测应用执行。 */
  @RestController
  @SaCheckLogin
  static class Endpoints {
    private final AccessStateStore store;
    private final AccessStateSecurity security;

    /** 保留实际身份与数据依赖，不接受门禁注入替身。 */
    Endpoints(AccessStateStore store, AccessStateSecurity security) {
      this.store = store;
      this.security = security;
    }

    /** 实际账号密码登录，只在私有响应中交付原生令牌。 */
    @SaIgnore
    @PostMapping("/gate/login")
    Envelope login(@RequestBody Login input) {
      return ok(Map.of("token", security.login(input.id(), input.password())));
    }

    /** 同一个令牌在独立进程中再次参与当前身份校验。 */
    @GetMapping("/gate/identity")
    Envelope identity() {
      return ok(Map.of("id", security.current().id()));
    }

    /** 登出由原生 Redis DAO 移除当前令牌。 */
    @PostMapping("/gate/logout")
    Envelope logout() {
      StpUtil.logout();
      return ok(null);
    }

    /** 列表只返回同时满足可信归属和数据范围的主键。 */
    @SaCheckPermission("read")
    @GetMapping("/gate/resources")
    Envelope resources() {
      return ok(store.resources(security.current()));
    }

    /** 操作权限不能代替单资源归属及范围判断。 */
    @SaCheckPermission("read")
    @GetMapping("/gate/resource/{id}")
    Envelope resource(@PathVariable("id") long id) {
      return ok(store.resource(id, security.current()));
    }

    /** 写入与读取共享可信范围，禁止客户端自报主体越权。 */
    @SaCheckPermission("write")
    @PostMapping("/gate/resource/{id}")
    Envelope write(@PathVariable("id") long id, @RequestBody Map<String, String> input) {
      store.write(id, security.current(), input.get("value"));
      return ok(null);
    }

    /** 管理变更使用原生权限及可信操作者，完成后下一请求读取新状态。 */
    @cn.dev33.satoken.annotation.SaCheckRole("manager")
    @SaCheckPermission("admin")
    @PostMapping("/gate/admin/state")
    Envelope change(@RequestBody Change input) {
      return ok(store.change(security.current().id(), input.id(), input.field(), input.value()));
    }

    /** 密码保存与全部会话撤销都完成后才允许报告成功。 */
    @PostMapping("/gate/password")
    Envelope password(@RequestBody PasswordChange input) {
      AccessStateStore.Account actor = security.current();
      store.password(actor, input.oldPassword(), input.newPassword());
      StpUtil.logout(actor.id());
      return ok(store.audit(actor.id(), actor.id(), "password", "changed", false));
    }

    /** 补录仅证明当前快照及当前操作者，原始缺失详情保持未知。 */
    @cn.dev33.satoken.annotation.SaCheckRole("manager")
    @SaCheckPermission("admin")
    @PostMapping("/gate/admin/reconcile")
    Envelope reconcile(@RequestBody Change input) {
      return ok(store.reconcile(security.current().id(), input.id()));
    }

    /** 所有成功场景共用固定外壳。 */
    private Envelope ok(Object data) {
      return new Envelope("SUCCESS", "完成", data);
    }
  }

  /** 类型化错误仅输出固定代码，避免数据库、令牌和密码进入响应。 */
  @RestControllerAdvice
  static class Errors {
    /** 无有效身份或登录凭证错误均拒绝认证。 */
    @ExceptionHandler({NotLoginException.class, AccessStateSecurity.BadCredentials.class})
    ResponseEntity<Envelope> unauthenticated() {
      return failure(401, "UNAUTHENTICATED");
    }

    /** 可信身份缺少操作、资源或范围条件时返回授权拒绝。 */
    @ExceptionHandler({
      NotPermissionException.class,
      cn.dev33.satoken.exception.NotRoleException.class
    })
    ResponseEntity<Envelope> forbidden() {
      return failure(403, "FORBIDDEN");
    }

    /** 防护限制不伪装为账号不存在或永久锁定。 */
    @ExceptionHandler(AccessStateSecurity.Limited.class)
    ResponseEntity<Envelope> limited() {
      return failure(429, "LIMITED");
    }

    /** 依赖错误与变更未完成不能归类为审计容错成功。 */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<Envelope> dependency() {
      return failure(503, "DEPENDENCY");
    }

    /** 固定外壳不携带异常消息、SQL 或请求材料。 */
    private ResponseEntity<Envelope> failure(int status, String code) {
      return ResponseEntity.status(status).body(new Envelope(code, "失败", null));
    }
  }
}
