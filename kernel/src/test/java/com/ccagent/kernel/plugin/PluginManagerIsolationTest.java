package com.ccagent.kernel.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 插件隔离加载集成测试
 * <p>通过 javac 构造真实插件 jar 放入插件目录，验证 B（隔离加载/卸载重载）+ C（init 失败回滚）：</p>
 * <ul>
 *   <li>好插件从独立 {@link PluginClassLoader} 加载，与内核类隔离</li>
 *   <li>坏插件（依赖类缺失 → NoClassDefFoundError、init 抛异常）不击穿内核，仅记录失败</li>
 *   <li>init 失败时 shutdown() 被调用，回滚半初始化资源（C）</li>
 *   <li>reload 后生成新的类副本（旧类可被 GC 回收），jar 句柄释放可删除（卸载）</li>
 * </ul>
 */
class PluginManagerIsolationTest {

    private static final String PKG = "com.ccagent.plugin.isotest";

    @TempDir
    Path tempDir;

    private Path pluginDir;
    private Path pluginJar;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(execOk("javac", "-version"), "javac 不可用，跳过插件隔离测试");

        // 1. 编写插件源码
        Path srcDir = tempDir.resolve("src");
        writeSource(srcDir, "GoodPlugin", """
                package %s;
                import com.ccagent.kernel.plugin.ExtensionPoint;
                import com.ccagent.kernel.plugin.PluginContext;
                public class GoodPlugin implements ExtensionPoint {
                    public static volatile boolean INITED = false;
                    public static volatile boolean SHUTDOWN = false;
                    @Override public void init(PluginContext context) { INITED = true; }
                    @Override public void shutdown() { SHUTDOWN = true; }
                }
                """.formatted(PKG));
        // 依赖类缺失：Helper 只编译不打进 jar，加载 BadDepPlugin 时触发 NoClassDefFoundError
        writeSource(srcDir, "BadDepPlugin", """
                package %s;
                import com.ccagent.kernel.plugin.ExtensionPoint;
                import com.ccagent.kernel.plugin.PluginContext;
                public class BadDepPlugin implements ExtensionPoint {
                    static final Helper H = new Helper();
                    @Override public void init(PluginContext context) {}
                    static class Helper {}
                }
                """.formatted(PKG));
        writeSource(srcDir, "BadInitPlugin", """
                package %s;
                import com.ccagent.kernel.plugin.ExtensionPoint;
                import com.ccagent.kernel.plugin.PluginContext;
                public class BadInitPlugin implements ExtensionPoint {
                    public static volatile boolean SHUTDOWN_CALLED = false;
                    @Override public void init(PluginContext context) { throw new RuntimeException("init boom"); }
                    @Override public void shutdown() { SHUTDOWN_CALLED = true; }
                }
                """.formatted(PKG));

        // 2. javac 编译（classpath 复用测试 classpath，含 kernel 的 ExtensionPoint）
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        String cp = System.getProperty("java.class.path");
        assumeTrue(execOk("javac", "-encoding", "UTF-8", "-d", classesDir.toString(), "-cp", cp,
                srcDir.resolve("GoodPlugin.java").toString(),
                srcDir.resolve("BadDepPlugin.java").toString(),
                srcDir.resolve("BadInitPlugin.java").toString()),
                "javac 编译插件源码失败");

        // 3. 打包插件 jar（排除 BadDepPlugin$Helper.class，制造依赖缺失）
        pluginDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginDir);
        pluginJar = pluginDir.resolve("test-plugins.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(pluginJar))) {
            packClass(zos, classesDir, PKG + ".GoodPlugin");
            packClass(zos, classesDir, PKG + ".BadDepPlugin");
            packClass(zos, classesDir, PKG + ".BadInitPlugin");
            putYaml(zos, "good.yaml", "iso-good", PKG + ".GoodPlugin");
            putYaml(zos, "bad-dep.yaml", "iso-bad-dep", PKG + ".BadDepPlugin");
            putYaml(zos, "bad-init.yaml", "iso-bad-init", PKG + ".BadInitPlugin");
        }
    }

    @Test
    void badPlugins_doNotCrashKernel_andInitRollbackIsInvoked() throws Exception {
        SimplePluginManager pm = new SimplePluginManager(pluginDir);
        SimplePluginContext ctx = new SimplePluginContext();

        List<ExtensionPoint> exts = pm.loadExtensions(PluginType.LISTENER, ctx);

        // 好插件加载成功，且由独立 ClassLoader 加载（隔离）
        assertEquals(1, exts.size(), "只有 GoodPlugin 应加载成功");
        ExtensionPoint good = exts.get(0);
        assertTrue(good.getClass().getClassLoader() instanceof PluginClassLoader,
                "GoodPlugin 应由独立 PluginClassLoader 加载");
        assertEquals(true, readStatic(good.getClass(), "INITED"), "GoodPlugin.init 应被调用");

        // 两个坏插件被隔离：不击穿内核，仅记录失败
        assertEquals(2, pm.getLoadReport().getFailedCount(), "坏插件应记录 2 个失败（依赖缺失 + init 异常）");

        // C：BadInitPlugin 的 shutdown() 应在 init 失败时被调用（半初始化资源回滚）
        ClassLoader childCL = good.getClass().getClassLoader();
        assertEquals(true, readStatic(loadPluginClass(childCL, PKG + ".BadInitPlugin"), "SHUTDOWN_CALLED"),
                "init 失败时应调用 shutdown() 回滚半初始化资源");

        // 内核仍可用：再次加载不受坏插件影响
        List<ExtensionPoint> again = pm.loadExtensions(PluginType.LISTENER, ctx);
        assertEquals(1, again.size());

        pm.shutdown();
    }

    @Test
    void reload_createsNewClassInstances_andJarHandleReleased() throws Exception {
        SimplePluginManager pm = new SimplePluginManager(pluginDir);
        SimplePluginContext ctx = new SimplePluginContext();

        ExtensionPoint first = pm.loadExtensions(PluginType.LISTENER, ctx).get(0);

        // 卸载重载：关闭旧类加载器 → 重新加载 → 新类副本（静态状态重置、类对象不同）
        List<ExtensionPoint> reloaded = pm.reloadExtensions(PluginType.LISTENER, ctx);
        assertEquals(1, reloaded.size());
        ExtensionPoint second = reloaded.get(0);
        assertNotSame(first.getClass(), second.getClass(), "重载后应产生新的类副本（旧类可被回收）");
        assertEquals(true, readStatic(second.getClass(), "INITED"), "新类副本应重新初始化");

        // 卸载后 jar 文件句柄释放（Windows 下可删除/替换）
        pm.closeAllLoaders();
        Files.delete(pluginJar);
        assertFalse(Files.exists(pluginJar), "closeAllLoaders 后插件 jar 应可删除（句柄已释放）");

        pm.shutdown();
    }

    // ============ 辅助 ============

    private Class<?> loadPluginClass(ClassLoader cl, String name) throws Exception {
        return Class.forName(name, false, cl);
    }

    private Object readStatic(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getField(fieldName);
            return f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void packClass(ZipOutputStream zos, Path classesDir, String fqcn) throws IOException {
        String path = fqcn.replace('.', '/') + ".class";
        Path file = classesDir.resolve(path);
        if (!Files.exists(file)) throw new IOException("class 不存在: " + file);
        zos.putNextEntry(new ZipEntry(path));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private void putYaml(ZipOutputStream zos, String name, String pluginName, String className) throws IOException {
        String yaml = "name: " + pluginName + "\ntype: LISTENER\nclass: " + className + "\n";
        zos.putNextEntry(new ZipEntry("META-INF/plugins/" + name));
        zos.write(yaml.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private void writeSource(Path dir, String className, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(className + ".java"), content, StandardCharsets.UTF_8);
    }

    private boolean execOk(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream is = p.getInputStream()) {
                is.readAllBytes();
            }
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
