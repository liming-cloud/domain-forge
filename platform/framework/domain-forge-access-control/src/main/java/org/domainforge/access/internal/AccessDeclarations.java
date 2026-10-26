package org.domainforge.access.internal;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Set;

/**
 * 访问声明的支持范围检查；实际登录、权限、角色及组合判定由原生 Sa-Token 执行。
 *
 * <p>注解读取复用原生解析函数，保证这里看到的声明与原生实际执行的声明一致。
 */
public final class AccessDeclarations {
    private static final Set<Class<? extends Annotation>> SUPPORTED =
            Set.of(SaIgnore.class, SaCheckLogin.class, SaCheckPermission.class, SaCheckRole.class, SaCheckOr.class);
    private static final List<Class<? extends Annotation>> UNSUPPORTED =
            List.of(SaCheckSafe.class, SaCheckHttpBasic.class, SaCheckHttpDigest.class, SaCheckDisable.class);

    /**
     * 框架自有处理器（如错误页）不按业务入口检查；应用控制器没有默认匿名豁免。
     */
    public static boolean framework(HandlerMethod method) {
        return method.getBeanType().getPackageName().startsWith("org.springframework.");
    }

    private static <A extends Annotation> A annotation(AnnotatedElement element, Class<A> kind) {
        return kind.cast(SaAnnotationStrategy.instance.getAnnotation.apply(element, kind));
    }

    /**
     * 同时检查方法和类；匿名与保护混合以及超出支持范围的声明视为配置错误。
     */
    public Policy policy(HandlerMethod method) {
        rejectUnexecutedSubclassPolicy(method);
        boolean anonymous = false;
        boolean protectedAccess = false;
        for (var element : List.of(method.getMethod(), method.getBeanType())) {
            anonymous |= annotation(element, SaIgnore.class) != null;
            protectedAccess |= validate(element);
        }
        if (anonymous && protectedAccess) throw invalid();
        if (anonymous) return Policy.ANONYMOUS;
        return protectedAccess ? Policy.PROTECTED : Policy.UNDECLARED;
    }

    /**
     * 原生按方法声明类读取类级注解；子类独有的类级声明不会被执行，因此拒绝。
     */
    private void rejectUnexecutedSubclassPolicy(HandlerMethod method) {
        var declaring = method.getMethod().getDeclaringClass();
        if (declaring == method.getBeanType()) return;
        for (var kind : SUPPORTED) {
            var onBean = annotation(method.getBeanType(), kind);
            if (onBean != null && !onBean.equals(annotation(declaring, kind))) throw invalid();
        }
    }

    /**
     * 返回元素是否带有受支持的保护声明；空声明、其他登录类型和未支持分支一律拒绝。
     */
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

    /**
     * 原生可执行但本产品未承诺的分支（含宿主额外登记的处理器）不能混入访问策略。
     */
    private void rejectUnsupportedAnnotations(AnnotatedElement element) {
        for (var kind : UNSUPPORTED) if (annotation(element, kind) != null) throw invalid();
        for (var kind : SaAnnotationStrategy.instance.annotationHandlerMap.keySet()) {
            if (!SUPPORTED.contains(kind) && annotation(element, kind.asSubclass(Annotation.class)) != null)
                throw invalid();
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

    /**
     * OR 只接受登录、权限、角色三种分支，不复制它们的运行判定和优先级。
     */
    private void or(SaCheckOr or) {
        int unsupported = or.safe().length + or.httpBasic().length + or.httpDigest().length
                + or.disable().length + or.append().length;
        if (unsupported > 0 || or.login().length + or.permission().length + or.role().length == 0) throw invalid();
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

    private IllegalStateException invalid() {
        return new IllegalStateException("访问策略声明无效或超出当前支持范围");
    }

    /**
     * 入口的访问策略；未声明的入口在请求时默认拒绝，匿名必须明确声明。
     */
    public enum Policy {
        ANONYMOUS,
        PROTECTED,
        UNDECLARED
    }
}
