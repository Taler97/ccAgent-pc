package com.ccagent.kernel.plugin;

/**
 * 扩展点标记接口
 * <p>所有可插拔组件（Expert、Tool、Listener）的扩展点接口都继承此接口。
 * 实现类通过 {@link #init(PluginContext)} 获取运行时依赖。</p>
 */
public interface ExtensionPoint {

    /**
     * 插件初始化：从上下文获取依赖
     * <p>PluginManager 实例化插件后调用此方法，插件在此获取 LlmPort、
     * ToolRegistry、EventBus 等依赖并完成内部初始化。</p>
     *
     * @param context 插件运行上下文
     */
    void init(PluginContext context);
}
