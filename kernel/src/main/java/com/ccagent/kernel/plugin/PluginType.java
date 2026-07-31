package com.ccagent.kernel.plugin;

/**
 * 插件类型枚举
 * <p>决定加载阶段：TOOL → EXPERT → LISTENER</p>
 */
public enum PluginType {
    /** 工具插件，最先加载 */
    TOOL,
    /** 专家插件，依赖 ToolRegistry 已就绪 */
    EXPERT,
    /** 监听器插件，依赖 EventBus 已就绪 */
    LISTENER
}
