package org.domainforge.access.backend.internal.execution;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

/** 前置声明检查只界定支持范围；原生负责实际登录、权限、角色及组合执行。 */
public final class AccessDeclarations {
  /** 同时检查方法和类，匿名与保护混合以及无法支持的声明在启动时拒绝。 */
  public Policy policy(HandlerMethod method) {
    rejectUnexecutedSubclassPolicy(method);
    boolean anonymous = false;
    boolean protectedAccess = false;
    for (var element : List.of(method.getMethod(), method.getBeanType())) {
      anonymous |= annotation(element, SaIgnore.class) != null;
      protectedAccess |= validate(element);
    }
    if (anonymous && protectedAccess) throw invalid();
    return anonymous ? Policy.ANONYMOUS : protectedAccess ? Policy.PROTECTED : Policy.UNDECLARED;
  }

  /** 原生执行依据方法声明类；子类独有声明无法被原生覆盖时拒绝该组合。 */
  private void rejectUnexecutedSubclassPolicy(HandlerMethod method) {
    var declaring = method.getMethod().getDeclaringClass();
    if (declaring == method.getBeanType()) return;
    for (var kind :
        List.of(
            SaIgnore.class,
            SaCheckLogin.class,
            SaCheckPermission.class,
            SaCheckRole.class,
            SaCheckOr.class)) {
      var onBean = annotation(method.getBeanType(), kind);
      if (onBean != null && !onBean.equals(annotation(declaring, kind))) throw invalid();
    }
  }

  /** 固定原生版本的其他安全分支一律拒绝，不能用空声明伪装登录保护。 */
  private boolean validate(AnnotatedElement element) {
    rejectUnsupportedAnnotations(element);
    var login = annotation(element, SaCheckLogin.class);
    var permission = annotation(element, SaCheckPermission.class);
    var role = annotation(element, SaCheckRole.class);
    var or = annotation(element, SaCheckOr.class);
    if (login != null) type(login.type());
    if (permission != null) permission(permission);
    if (role != null) role(role);
    if (or != null) or(or);
    return login != null || permission != null || role != null || or != null;
  }

  /** 声明范围检查与支持分支的数据校验分开，拒绝原生可执行但本产品未承诺的分支。 */
  private void rejectUnsupportedAnnotations(AnnotatedElement element) {
    for (var kind :
        List.of(
            cn.dev33.satoken.annotation.SaCheckSafe.class,
            cn.dev33.satoken.annotation.SaCheckHttpBasic.class,
            cn.dev33.satoken.annotation.SaCheckHttpDigest.class,
            cn.dev33.satoken.annotation.SaCheckDisable.class))
      if (AnnotatedElementUtils.hasAnnotation(element, kind)) throw invalid();
    for (var kind : SaAnnotationStrategy.instance.annotationHandlerMap.keySet()) {
      if (java.util.Set.of(
              SaCheckLogin.class,
              SaCheckPermission.class,
              SaCheckRole.class,
              SaCheckOr.class,
              SaIgnore.class)
          .contains(kind)) continue;
      if (AnnotatedElementUtils.hasAnnotation(
          element, kind.asSubclass(java.lang.annotation.Annotation.class))) throw invalid();
    }
  }

  private void permission(SaCheckPermission permission) {
    type(permission.type());
    values(permission.value());
    for (String alternative : permission.orRole())
      if (alternative == null || alternative.isBlank()) throw invalid();
  }

  private void role(SaCheckRole role) {
    type(role.type());
    values(role.value());
  }

  /** OR仅接受本产品支持的三种分支，不复制它们的运行判定和优先级。 */
  private void or(SaCheckOr or) {
    if (or.safe().length
                + or.httpBasic().length
                + or.httpDigest().length
                + or.disable().length
                + or.append().length
            > 0
        || or.login().length + or.permission().length + or.role().length == 0) throw invalid();
    for (var login : or.login()) type(login.type());
    for (var permission : or.permission()) permission(permission);
    for (var role : or.role()) role(role);
  }

  private void type(String type) {
    if (!type.isEmpty() && !type.equals("login")) throw invalid();
  }

  private void values(String[] values) {
    if (values.length == 0) throw invalid();
    for (String value : values) if (value == null || value.isBlank()) throw invalid();
  }

  private static <A extends java.lang.annotation.Annotation> A annotation(
      AnnotatedElement element, Class<A> kind) {
    return AnnotatedElementUtils.findMergedAnnotation(element, kind);
  }

  private IllegalStateException invalid() {
    return new IllegalStateException("访问策略声明无效或超出当前支持范围");
  }

  /** 未声明仍在请求时默认拒绝，匿名必须具有明确声明。 */
  public enum Policy {
    ANONYMOUS,
    PROTECTED,
    UNDECLARED
  }
}
