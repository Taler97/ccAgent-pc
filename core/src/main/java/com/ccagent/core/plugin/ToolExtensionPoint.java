package com.ccagent.core.plugin;

import com.ccagent.core.tool.ToolExecutor;
import com.ccagent.kernel.plugin.ExtensionPoint;

/**
 * Tool 扩展点接口
 * <p>工具插件实现此接口。PluginManager 实例化后调用 {@link #init} 注入依赖，
 * 随后由 DI 模块注册到 ToolRegistry。</p>
 */
public interface ToolExtensionPoint extends ToolExecutor, ExtensionPoint {
}
