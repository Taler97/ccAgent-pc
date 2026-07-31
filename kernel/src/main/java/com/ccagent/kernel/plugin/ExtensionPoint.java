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

    /**
     * 插件清理（默认空实现）
     * <p>插件卸载或 init 失败回滚时调用，插件在此释放 init 阶段创建的线程池等资源。
     * init 失败时 PluginManager 会调用此方法，清理半初始化资源。</p>
     */
    default void shutdown() {}
}
