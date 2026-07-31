package com.ccagent.core.tool;

import com.ccagent.api.model.response.ExecutionResult;

import java.util.Map;

/**
 * 工具执行器接口（内部）
 * <p>每个具体工具实现此接口，由 DefaultToolRegistry 统一管理。</p>
 */
public interface ToolExecutor {

    /**
     * 执行工具
     *
     * @param args          参数映射
     * @param workspaceRoot 工作区根路径
     * @return 执行结果
     */
    ExecutionResult execute(Map<String, Object> args, String workspaceRoot);

    /**
     * @return 工具名称
     */
    String getName();

    /**
     * @return 工具描述
     */
    String getDescription();

    /**
     * @return 是否只读
     */
    boolean isReadOnly();
}
