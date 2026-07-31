package com.ccagent.core.plugin;

import com.ccagent.kernel.plugin.ExtensionPoint;

/**
 * Listener 扩展点接口
 * <p>事件监听器插件实现此接口。在 {@link #init} 中从 PluginContext 获取 EventBus
 * 并订阅事件，实现事件驱动的治理逻辑（审计、回滚、统计等）。</p>
 */
public interface ListenerExtensionPoint extends ExtensionPoint {
}
