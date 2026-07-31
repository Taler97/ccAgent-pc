package com.ccagent.core.plugin;

import com.ccagent.api.port.Expert;
import com.ccagent.kernel.plugin.ExtensionPoint;

/**
 * Expert 扩展点接口
 * <p>专家插件实现此接口。PluginManager 实例化后调用 {@link #init} 注入依赖，
 * 随后由 DI 模块注册到 ExpertRegistry。</p>
 */
public interface ExpertExtensionPoint extends Expert, ExtensionPoint {
}
