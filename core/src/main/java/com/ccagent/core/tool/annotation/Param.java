package com.ccagent.core.tool.annotation;

import java.lang.annotation.*;

/**
 * 参数注解
 * <p>标注在工具方法的参数上，定义参数的元数据（供 LLM 理解）。</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Param {

    /**
     * 参数名
     */
    String name();

    /**
     * 参数描述（供 LLM 理解）
     */
    String description();

    /**
     * 参数类型（string / integer / boolean / object / array）
     */
    String type() default "string";

    /**
     * 是否必填
     */
    boolean required() default true;
}
