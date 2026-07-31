package com.ccagent.kernel.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 默认 PluginManager 实现
 * <p>启动时扫描 classpath 下 META-INF/plugins/*.yaml，解析为 PluginDescriptor，
 * 按 type + dependencies 拓扑排序后反射实例化。</p>
 *
 * <h3>加载流程</h3>
 * <ol>
 *   <li>扫描 classpath 下所有 plugin.yaml</li>
 *   <li>按 type 过滤 + dependencies 拓扑排序</li>
 *   <li>反射创建无参实例 → 调用 init(context)</li>
 *   <li>返回已初始化的扩展点列表</li>
 * </ol>
 */
public class SimplePluginManager implements PluginManager {

    private static final Logger LOG = Logger.getLogger(SimplePluginManager.class.getName());
    private static final String PLUGIN_DIR = "META-INF/plugins";

    private final ObjectMapper yamlMapper;
    private List<PluginDescriptor> allDescriptors;
    private int loadedCount = 0;
    private PluginLoadReport loadReport;

    public SimplePluginManager() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.allDescriptors = Collections.emptyList();
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
        return descriptors;
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
     */
    private ExtensionPoint instantiate(PluginDescriptor desc, PluginContext context) {
        try {
            Class<?> clazz = Class.forName(desc.getClassName());
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (instance instanceof ExtensionPoint ep) {
                ep.init(context);
                return ep;
            } else {
                String reason = "插件类未实现 ExtensionPoint 接口";
                LOG.warning(reason + ": " + desc.getClassName());
                if (loadReport != null) loadReport.recordFailed(desc, reason);
            }
        } catch (ClassNotFoundException e) {
            String reason = "插件类未找到";
            LOG.warning(reason + ": " + desc.getClassName());
            if (loadReport != null) loadReport.recordFailed(desc, reason);
        } catch (NoSuchMethodException e) {
            String reason = "插件类缺少无参构造函数";
            LOG.warning(reason + ": " + desc.getClassName());
            if (loadReport != null) loadReport.recordFailed(desc, reason);
        } catch (Exception e) {
            String reason = "插件实例化失败: " + e.getMessage();
            LOG.warning(reason + " (" + desc.getClassName() + ")");
            if (loadReport != null) loadReport.recordFailed(desc, reason);
        }
        return null;
    }
}
