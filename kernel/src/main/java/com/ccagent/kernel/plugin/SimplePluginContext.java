package com.ccagent.kernel.plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 PluginContext 实现
 * <p>内部维护一个服务注册表，DI 模块在初始化时注册核心服务（EventBus、
 * LlmPort、ToolRegistry 等），插件通过 getService() 按类型获取。</p>
 */
public class SimplePluginContext implements PluginContext {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    /**
     * 注册服务到上下文
     *
     * @param serviceType 服务接口的 Class
     * @param instance    服务实例
     * @param <T>         服务类型
     */
    public <T> void registerService(Class<T> serviceType, T instance) {
        if (instance != null) {
            services.put(serviceType, instance);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getService(Class<T> serviceType) {
        return (T) services.get(serviceType);
    }
}
