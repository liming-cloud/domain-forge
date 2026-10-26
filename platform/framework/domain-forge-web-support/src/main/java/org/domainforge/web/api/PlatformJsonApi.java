package org.domainforge.web.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 明确选择平台 JSON 异常处理的 Controller 标记；不自动注册 Controller 或包装返回值。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformJsonApi {
}
