package com.ccagent.kernel.plugin;

/**
 * 插件运行上下文
 * <p>提供插件运行所需的依赖服务。PluginManager 在加载插件时创建并传入。
 * 核心组件（EventBus、LlmPort、ToolRegistry 等）由 DI 模块注册到上下文中。</p>
 */
public interface PluginContext {

    /**
     * 按类型获取服务
     *
     * @param serviceType 服务接口的 Class
     * @param <T>         服务类型
     * @return 服务实例，未注册则返回 null
     */
    <T> T getService(Class<T> serviceType);
}
