package org.domainforge.web.internal;

import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

/**
 * 拒绝同一接入入口上的可判定兜底处理竞争，避免顺序变化静默改变协议。
 */
public final class AdviceConflicts implements SmartInitializingSingleton {
    private final ApplicationContext context;
    private final List<RequestMappingHandlerMapping> handlers;

    /**
     * 取得宿主的真实 MVC 注册与 Advice 元数据。
     *
     * @param context  当前容器
     * @param handlers MVC 注册表
     */
    public AdviceConflicts(ApplicationContext context, List<RequestMappingHandlerMapping> handlers) {
        this.context = context;
        this.handlers = handlers;
    }

    /**
     * 本地处理器优先于 Advice，同样禁止竞争的兜底声明。
     *
     * @param controller 接入类型
     * @param advice     宿主 Advice
     */
    private static void check(Class<?> controller, List<ControllerAdviceBean> advice) {
        rejectCatchAll(controller);
        for (ControllerAdviceBean bean : advice) {
            if (bean.getBeanType() != PlatformExceptionAdvice.class
                    && bean.isApplicableToBeanType(controller)) {
                rejectCatchAll(bean.getBeanType());
            }
        }
    }

    /**
     * 沿用 MVC 的有效方法解析，尊重覆盖关系并拒绝仍生效的兜底声明。
     *
     * @param type 待检查类型
     */
    private static void rejectCatchAll(Class<?> type) {
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(type);
        if (resolver.resolveMethodByExceptionType(Exception.class) != null
                || resolver.resolveMethodByExceptionType(RuntimeException.class) != null) {
            throw new IllegalStateException("平台 JSON 入口存在竞争的兜底 Advice：" + type.getName());
        }
    }

    /**
     * 所有单例初始化后，按真实 Controller 类型与 Advice 选择条件检查冲突。
     */
    @Override
    public void afterSingletonsInstantiated() {
        List<ControllerAdviceBean> advice = ControllerAdviceBean.findAnnotatedBeans(context);
        handlers.stream()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream())
                .map(handler -> handler.getBeanType())
                .distinct()
                .filter(type -> AnnotatedElementUtils.hasAnnotation(type, PlatformJsonApi.class))
                .forEach(type -> check(type, advice));
    }
}
