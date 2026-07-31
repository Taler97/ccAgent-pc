package com.ccagent.core.tool.annotation;

import java.lang.annotation.*;

/**
 * 工具注解
 * <p>标注在工具实现类上，启动时由 DefaultToolRegistry 自动扫描注册。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

    /**
     * 工具唯一标识（为空则取类名去 Tool 后缀，如 ReadFileTool → readFile）
     */
    String name() default "";

    /**
     * 功能描述（供 LLM 理解）
     */
    String description();

    /**
     * 是否只读（供治理层工具拦截使用）
     */
    boolean readOnly() default false;
}
