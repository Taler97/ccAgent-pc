package com.ccagent.kernel.plugin;

import java.util.List;

/**
 * 插件管理器
 * <p>负责扫描、加载、实例化、注册插件扩展点。
 * 启动时扫描 classpath 下所有 plugin.yaml，按类型分阶段加载。</p>
 */
public interface PluginManager {

    /**
     * 加载指定类型的所有扩展点
     * <p>扫描 → 拓扑排序 → 反射实例化 → 调用 init(context) → 返回扩展点列表</p>
     *
     * @param type    插件类型（TOOL / EXPERT / LISTENER）
     * @param context 插件上下文（包含已注册的核心服务）
     * @return 已实例化并初始化的扩展点列表，调用方按需类型转换
     */
    List<ExtensionPoint> loadExtensions(PluginType type, PluginContext context);

    /**
     * 获取所有已扫描的插件描述符
     */
    List<PluginDescriptor> getLoadedDescriptors();

    /**
     * 获取已加载的扩展点总数
     */
    int getLoadedCount();

    /**
     * 获取插件加载报告（包含成功/失败详情）
     */
    PluginLoadReport getLoadReport();

    /**
     * 重新加载指定类型的所有扩展点（默认与首次加载一致）
     * <p>关闭旧隔离类加载器 → 重新扫描 → 返回新实例列表，由调用方替换旧实例完成热插拔。</p>
     */
    default List<ExtensionPoint> reloadExtensions(PluginType type, PluginContext context) {
        return loadExtensions(type, context);
    }

    /**
     * 关闭全部隔离类加载器（卸载插件，类可被 GC 回收）
     */
    default void closeAllLoaders() {}

    /**
     * 关闭插件管理器（释放隔离类加载器）
     */
    default void shutdown() {}
}
