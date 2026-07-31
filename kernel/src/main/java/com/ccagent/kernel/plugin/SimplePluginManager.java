package com.ccagent.kernel.plugin;

import com.ccagent.infrastructure.constant.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 默认 PluginManager 实现
 * <p>启动时扫描 classpath 及插件目录下的 META-INF/plugins/*.yaml，解析为 PluginDescriptor，
 * 按 type + dependencies 拓扑排序后反射实例化。</p>
 *
 * <h3>加载流程</h3>
 * <ol>
 *   <li>扫描 classpath 与插件目录下所有 plugin.yaml</li>
 *   <li>按 type 过滤 + dependencies 拓扑排序</li>
 *   <li>反射创建无参实例 → 调用 init(context)</li>
 *   <li>返回已初始化的扩展点列表</li>
 * </ol>
 *
 * <h3>隔离加载</h3>
 * <ul>
 *   <li>插件目录（Constants.PLUGIN_DIR）中的 jar 由独立 {@link PluginClassLoader}（child-first）加载：
 *       插件类与内核/其他插件类隔离，类冲突互不影响，close() 后可卸载重载。</li>
 *   <li>classpath 插件保持原有加载方式（兼容）。</li>
 *   <li>单个插件任何异常（含 NoClassDefFoundError 等 Error）都不影响其他插件与内核。</li>
 * </ul>
 */
public class SimplePluginManager implements PluginManager {

    private static final Logger LOG = Logger.getLogger(SimplePluginManager.class.getName());
    private static final String PLUGIN_DIR = "META-INF/plugins";

    private final ObjectMapper yamlMapper;
    private final Path pluginDir;
    private List<PluginDescriptor> allDescriptors;
    private int loadedCount = 0;
    private PluginLoadReport loadReport;
    /** 插件 jar URL -> 隔离类加载器（同一 jar 内多个插件共享） */
    private final Map<String, PluginClassLoader> pluginLoaders = new ConcurrentHashMap<>();

    public SimplePluginManager() {
        this(Paths.get(Constants.PLUGIN_DIR));
    }

    public SimplePluginManager(Path pluginDir) {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.allDescriptors = Collections.emptyList();
        this.pluginDir = pluginDir;
    }

    @Override
    public List<ExtensionPoint> loadExtensions(PluginType type, PluginContext context) {
        // 延迟扫描
        if (allDescriptors.isEmpty()) {
            allDescriptors = scanDescriptors();
            LOG.info("扫描到 " + allDescriptors.size() + " 个插件描述符");
            loadReport = new PluginLoadReport(allDescriptors.size());
        }

        // 按 type 过滤
        List<PluginDescriptor> typedDescs = allDescriptors.stream()
                .filter(d -> d.getType() == type)
                .collect(Collectors.toList());

        // 拓扑排序
        List<PluginDescriptor> sorted = topologicalSort(typedDescs);

        // 实例化 + 初始化
        List<ExtensionPoint> extensions = new ArrayList<>();
        for (PluginDescriptor desc : sorted) {
            ExtensionPoint ext = instantiate(desc, context);
            if (ext != null) {
                extensions.add(ext);
                loadedCount++;
                loadReport.recordLoaded(desc);
                LOG.info("已加载插件: " + desc);
            }
        }
        return extensions;
    }

    @Override
    public List<PluginDescriptor> getLoadedDescriptors() {
        return Collections.unmodifiableList(allDescriptors);
    }

    @Override
    public int getLoadedCount() {
        return loadedCount;
    }

    @Override
    public PluginLoadReport getLoadReport() {
        if (loadReport == null) {
            loadReport = new PluginLoadReport(allDescriptors.size());
        }
        return loadReport;
    }

    // ============ 扫描 ============

    /**
     * 扫描 classpath 下 META-INF/plugins/ 目录中的所有 .yaml 文件
     */
    private List<PluginDescriptor> scanDescriptors() {
        List<PluginDescriptor> descriptors = new ArrayList<>();
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();

            Enumeration<URL> resources = cl.getResources(PLUGIN_DIR);
            while (resources.hasMoreElements()) {
                URL dirUrl = resources.nextElement();
                // 确保 dirUrl 以斜杠结尾，否则 new URL(dirUrl, yamlFile) 拼接会错误
                String dirUrlStr = dirUrl.toString();
                if (!dirUrlStr.endsWith("/")) {
                    dirUrl = new URL(dirUrlStr + "/");
                }
                List<String> yamlFiles = listYamlFiles(dirUrl);
                for (String yamlFile : yamlFiles) {
                    URL fileUrl = new URL(dirUrl, yamlFile);
                    try (InputStream is = fileUrl.openStream()) {
                        PluginDescriptor desc = yamlMapper.readValue(is, PluginDescriptor.class);
                        if (desc != null && desc.getClassName() != null && desc.getType() != null) {
                            descriptors.add(desc);
                        }
                    } catch (Exception e) {
                        LOG.warning("解析插件描述文件失败: " + yamlFile + " - " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOG.warning("扫描插件目录失败: " + e.getMessage());
        }

        scanPluginDir(descriptors);
        return descriptors;
    }

    /**
     * 扫描插件目录（Constants.PLUGIN_DIR）下的所有 jar
     * <p>jar 内的 META-INF/plugins/*.yaml 描述符标记为隔离加载（独立 ClassLoader）。</p>
     */
    private void scanPluginDir(List<PluginDescriptor> descriptors) {
        if (pluginDir == null) return;
        File dir = pluginDir.toFile();
        if (!dir.isDirectory()) return;

        File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null) return;

        for (File jar : jars) {
            try (JarFile jf = new JarFile(jar)) {
                String entryPrefix = PLUGIN_DIR + "/";
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith(entryPrefix)
                            || !(name.endsWith(".yaml") || name.endsWith(".yml"))
                            || name.length() <= entryPrefix.length()) {
                        continue;
                    }
                    try (InputStream is = jf.getInputStream(entry)) {
                        PluginDescriptor desc = yamlMapper.readValue(is, PluginDescriptor.class);
                        if (desc != null && desc.getClassName() != null && desc.getType() != null) {
                            desc.setIsolated(true);
                            desc.setSourceUrl(jar.toURI().toURL().toString());
                            descriptors.add(desc);
                        }
                    } catch (Exception e) {
                        LOG.warning("解析插件描述文件失败: " + jar.getName() + " - " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOG.warning("读取插件 jar 失败: " + jar.getName() + " - " + e.getMessage());
            }
        }
    }

    /**
     * 列出目录下的 .yaml 文件（兼容文件系统和 jar 内资源）
     */
    private List<String> listYamlFiles(URL dirUrl) throws IOException {
        List<String> files = new ArrayList<>();
        String protocol = dirUrl.getProtocol();

        if ("file".equals(protocol)) {
            try {
                File dir = new File(dirUrl.toURI());
                File[] children = dir.listFiles();
                if (children != null) {
                    for (File f : children) {
                        if (f.getName().endsWith(".yaml") || f.getName().endsWith(".yml")) {
                            files.add(f.getName());
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warning("列出插件目录失败: " + dirUrl + " - " + e.getMessage());
            }
        } else if ("jar".equals(protocol)) {
            JarURLConnection jarConn = (JarURLConnection) dirUrl.openConnection();
            try (JarFile jar = jarConn.getJarFile()) {
                String entryPrefix = PLUGIN_DIR + "/";
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith(entryPrefix)
                            && (name.endsWith(".yaml") || name.endsWith(".yml"))
                            && name.length() > entryPrefix.length()) {
                        files.add(name.substring(entryPrefix.length()));
                    }
                }
            }
        }
        return files;
    }

    // ============ 拓扑排序 ============

    /**
     * 按 dependencies 字段做拓扑排序（被依赖的先加载）
     */
    private List<PluginDescriptor> topologicalSort(List<PluginDescriptor> descriptors) {
        if (descriptors.isEmpty()) return descriptors;

        Map<String, PluginDescriptor> byName = new LinkedHashMap<>();
        for (PluginDescriptor d : descriptors) {
            byName.put(d.getName(), d);
        }

        List<PluginDescriptor> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (PluginDescriptor d : descriptors) {
            visit(d, byName, visited, visiting, sorted);
        }
        return sorted;
    }

    private void visit(PluginDescriptor d, Map<String, PluginDescriptor> byName,
                       Set<String> visited, Set<String> visiting,
                       List<PluginDescriptor> sorted) {
        if (visited.contains(d.getName())) return;
        if (visiting.contains(d.getName())) {
            LOG.warning("检测到循环依赖: " + d.getName());
            return;
        }
        visiting.add(d.getName());

        if (d.getDependencies() != null) {
            for (String dep : d.getDependencies()) {
                PluginDescriptor depDesc = byName.get(dep);
                if (depDesc != null) {
                    visit(depDesc, byName, visited, visiting, sorted);
                }
            }
        }

        visiting.remove(d.getName());
        visited.add(d.getName());
        sorted.add(d);
    }

    // ============ 实例化 ============

    /**
     * 反射创建实例并调用 init(context)
     * <p>隔离插件用独立 {@link PluginClassLoader} 加载（child-first）；任何异常
     * （含 NoClassDefFoundError、ExceptionInInitializerError 等 Error）都被捕获并记录，
     * 单插件失败不影响其他插件与内核。init 失败时调用 shutdown() 回滚半初始化资源。</p>
     */
    private ExtensionPoint instantiate(PluginDescriptor desc, PluginContext context) {
        Object instance = null;
        try {
            Class<?> clazz;
            if (desc.isIsolated() && desc.getSourceUrl() != null) {
                clazz = Class.forName(desc.getClassName(), true,
                        getOrCreateLoader(desc.getSourceUrl()));
            } else {
                clazz = Class.forName(desc.getClassName());
            }

            instance = clazz.getDeclaredConstructor().newInstance();
            if (instance instanceof ExtensionPoint ep) {
                try {
                    ep.init(context);
                } catch (Throwable t) {
                    // init 失败：记录并回滚半初始化资源，不击穿内核
                    recordFailure(desc, "插件 init 失败: " + t);
                    rollbackInit(desc, instance);
                    return null;
                }
                return ep;
            } else {
                recordFailure(desc, "插件类未实现 ExtensionPoint 接口");
            }
        } catch (ClassNotFoundException e) {
            recordFailure(desc, "插件类未找到: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            recordFailure(desc, "插件类缺少无参构造函数");
        } catch (LinkageError e) {
            // 依赖类缺失（NoClassDefFoundError）、静态初始化失败（ExceptionInInitializerError）等
            recordFailure(desc, "插件类加载/链接失败（依赖缺失或类冲突）: " + e);
        } catch (Throwable t) {
            recordFailure(desc, "插件实例化失败: " + t);
            rollbackInit(desc, instance);
        }
        return null;
    }

    private void recordFailure(PluginDescriptor desc, String reason) {
        LOG.warning(reason + " (" + desc.getClassName() + ")");
        if (loadReport != null) loadReport.recordFailed(desc, reason);
    }

    /**
     * init 失败回滚：调用插件的 shutdown() 释放半初始化资源
     */
    private void rollbackInit(PluginDescriptor desc, Object instance) {
        if (instance instanceof ExtensionPoint ep) {
            try {
                ep.shutdown();
            } catch (Throwable t) {
                LOG.warning("插件回滚清理失败: " + desc.getClassName() + " - " + t);
            }
        }
    }

    /**
     * 获取（或创建）插件 jar 的隔离类加载器
     */
    private PluginClassLoader getOrCreateLoader(String sourceUrl) {
        return pluginLoaders.computeIfAbsent(sourceUrl, url -> {
            try {
                return new PluginClassLoader(new URL[]{new URL(url)}, getClass().getClassLoader());
            } catch (Exception e) {
                throw new IllegalStateException("插件 jar URL 无效: " + url, e);
            }
        });
    }

    /**
     * 重新加载指定类型的所有扩展点
     * <p>关闭全部隔离类加载器（卸载旧插件类，可被 GC 回收），重新扫描描述符并实例化。
     * 返回新实例列表，由调用方决定是否替换旧实例（重新装配）。</p>
     */
    @Override
    public synchronized List<ExtensionPoint> reloadExtensions(PluginType type, PluginContext context) {
        closeAllLoaders();
        allDescriptors = Collections.emptyList();
        loadReport = null;
        loadedCount = 0;
        return loadExtensions(type, context);
    }

    /**
     * 关闭全部隔离类加载器（卸载插件，类可被 GC 回收）
     */
    @Override
    public synchronized void closeAllLoaders() {
        for (PluginClassLoader loader : pluginLoaders.values()) {
            try {
                loader.close();
            } catch (IOException e) {
                LOG.warning("关闭插件类加载器失败: " + e.getMessage());
            }
        }
        pluginLoaders.clear();
    }

    /**
     * 关闭插件管理器（释放隔离类加载器）
     */
    @Override
    public void shutdown() {
        closeAllLoaders();
    }
}
