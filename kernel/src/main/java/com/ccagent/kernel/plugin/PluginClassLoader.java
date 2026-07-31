package com.ccagent.kernel.plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 插件类加载器（child-first 委托）
 * <p>为插件目录中的 jar 提供独立的类加载空间，实现类冲突隔离与卸载重载：</p>
 * <ul>
 *   <li><b>parent-first</b>：内核/API/核心/异常/基础设施包（com.ccagent.kernel、com.ccagent.api、
 *       com.ccagent.core、com.ccagent.exception、com.ccagent.infrastructure）以及 JDK/SLF4J 等
 *       始终从父加载器解析，保证插件实现的接口与内核使用的接口是同一个 Class 对象（类型兼容）。</li>
 *   <li><b>child-first</b>：插件自身类（com.ccagent.plugin.*）与第三方依赖类优先从插件 jar 加载，
 *       与内核及其他插件隔离；close() 后类可被 GC 回收，支持卸载。</li>
 * </ul>
 */
public class PluginClassLoader extends URLClassLoader {

    /** 始终从父加载器解析的包前缀 */
    private static final String[] PARENT_FIRST_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "jdk.internal.",
            "com.ccagent.kernel.", "com.ccagent.api.", "com.ccagent.core.",
            "com.ccagent.exception.", "com.ccagent.infrastructure.",
            "org.slf4j.", "org.apache.logging."
    };

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                boolean parentFirst = isParentFirst(name);
                if (parentFirst) {
                    c = tryLoadFromParent(name);
                }
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // 插件 jar 中没有，回退父加载器
                    }
                }
                if (c == null) {
                    c = tryLoadFromParent(name);
                }
            }
            if (c == null) {
                throw new ClassNotFoundException(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Class<?> tryLoadFromParent(String name) {
        try {
            return getParent().loadClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
