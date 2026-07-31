package com.ccagent.core.expert.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 专家注解
 * <p>标记专家类，并声明该专家可用的工具列表。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExpertAnnotation {
    /**
     * 专家名称
     */
    String name() default "";

    /**
     * 专家描述
     */
    String description() default "";

    /**
     * 专家分类
     */
    String category() default "";

    /**
     * 该专家可用的工具名称列表
     */
    String[] tools() default {};
}
