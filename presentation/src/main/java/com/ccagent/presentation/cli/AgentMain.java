package com.ccagent.presentation.cli;

import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.infrastructure.config.ConfigLoader;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.logging.LogConfig;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.presentation.ws.CloudWsSession;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * ccAgent 唯一入口
 *
 * <h3>启动形态</h3>
 * <ul>
 *   <li><b>默认（无参数）</b> — 本地 CLI 交互模式：引擎嵌入进程，直接对话，不连云端</li>
 *   <li><b>--background</b> — 远程模式（后台运行）：引擎 + WS 云端通道，常驻等待 Web 调度（旧参数 --headless 兼容）</li>
 *   <li><b>--standalone</b> — （已废弃）旧版单进程模式，建议使用默认 CLI 模式</li>
 * </ul>
 *
 * <p>所有形态共用同一套引擎组装（{@link DependencyGraph}）与单实例锁（{@link DaemonLock}），
 * 同一时刻只有一个引擎进程，数据始终单份。</p>
 */
public class AgentMain {

    private static final Logger LOG = Logger.getLogger(AgentMain.class.getName());

    public static void main(String[] args) {
        boolean background = false;
        boolean standalone = false;

        for (String arg : args) {
            switch (arg) {
                case "--background", "--headless" -> background = true;
                case "--standalone" -> { standalone = true; background = false; }
            }
        }

        if (standalone) {
            // 保留旧版单进程模式（兼容），标记 deprecated
            System.err.println("[WARN] --standalone 已废弃，请使用默认 CLI 模式（不加参数）");
            OldAgentMain.main(args);
            return;
        }

        // ===== 公共启动流程 =====
        try {
            // 1. 单实例锁：引擎已在运行（如 Web 后台使用中）则拒绝启动（方案 A）
            DaemonLock lock = DaemonLock.tryAcquire();
            if (lock == null) {
                System.err.println("引擎已在后台运行（可能通过 Web 使用中）。");
                System.err.println("请选择：1) 直接使用 Web 交互；2) 先停止后台引擎再启动。");
                System.exit(1);
            }

            // 2. 配置 + 工作区 + 日志
            AppConfig config = ConfigLoader.load();
            autoSetupWorkDir(config);
            LogConfig.init();

            // 3. 引擎组装（唯一一次）
            DependencyGraph graph = new DependencyGraph();
            graph.assemble(config);

            // 3.5 恢复中断的执行计划（异步，不阻塞启动）
            //    查找上次崩溃时状态仍为 RUNNING 的 Plan，从磁盘恢复黑板产物，重跑未完成步骤
            Thread.startVirtualThread(() -> {
                try {
                    int recovered = graph.getOrchestrator().recoverInterruptedPlans();
                    if (recovered > 0) {
                        LOG.info("已恢复 " + recovered + " 个中断的执行计划");
                    }
                } catch (Exception e) {
                    LOG.warning("恢复中断计划失败: " + e.getMessage());
                }
            });

            // 4. 按形态挂载交互层
            if (background) {
                runBackground(config, graph, lock);
            } else {
                runLocalCli(config, graph, lock);
            }

        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ==================== 形态一：默认 CLI 交互 ====================

    /**
     * 本地 CLI 交互模式：引擎嵌入进程，直接对话。
     */
    private static void runLocalCli(AppConfig config, DependencyGraph graph, DaemonLock lock) {
        try {
            StandaloneAgentRunner runner = new StandaloneAgentRunner(
                    graph.getGovernanceGateway(),
                    config,
                    graph.getSyncScheduler(),
                    graph.getLocalDataRepository(),
                    graph.getExpertRegistry()
            );
            runner.start();

            graph.shutdown();
            lock.close();

        } catch (Exception e) {
            System.err.println("CLI 交互模式启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ==================== 形态二：远程模式（--background） ====================

    private static volatile boolean running = true;

    /**
     * 远程模式（后台运行）：引擎 + WS 云端通道，接受 Cloud Server 远程调度，无 CLI 交互。
     */
    private static void runBackground(AppConfig config, DependencyGraph graph, DaemonLock lock) {
        try {
            // 启动 WebSocket 客户端，连接 Cloud Server（可选）
            CloudWsSession wsSession = startCloudWsClient(config, graph);

            // 远程模式同样启用数据同步（token_stats / conversation 上传云端）
            if (graph.getSyncScheduler() != null) {
                graph.getSyncScheduler().start();
                graph.getSyncScheduler().syncNow();
                System.out.println("数据同步调度已启动");
            }

            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║     ccAgent 引擎已启动（远程模式）       ║");
            System.out.println("║  引擎就绪，等待任务调度                    ║");
            if (wsSession != null) {
                System.out.println("║  Cloud: " + config.getWebsocket().getUrl() + "          ║");
            }
            System.out.println("║  输入 /exit 停止                          ║");
            System.out.println("╚══════════════════════════════════════════╝");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running = false;
                if (wsSession != null) wsSession.stop();
                graph.shutdown();
                lock.close();
                System.out.println("\nccAgent 引擎已停止");
            }));

            // 监听控制台输入，支持 /exit 优雅停止
            Thread consoleListener = new Thread(() -> {
                try (java.util.Scanner scanner = new java.util.Scanner(System.in)) {
                    while (running && scanner.hasNextLine()) {
                        String line = scanner.nextLine().trim();
                        if ("/exit".equalsIgnoreCase(line)) {
                            System.out.println("正在停止引擎...");
                            System.exit(0);
                        }
                    }
                }
            }, "console-listener");
            consoleListener.setDaemon(true);
            consoleListener.start();

            // 保持主线程运行
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("远程模式启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 启动 WebSocket 客户端（可选），连接 Cloud Server 接受远程调度。
     */
    private static CloudWsSession startCloudWsClient(AppConfig config, DependencyGraph graph) {
        if (config.getWebsocket() == null || config.getWebsocket().getUrl() == null
                || config.getWebsocket().getUrl().isBlank()) {
            return null;
        }
        try {
            CloudWsSession session = new CloudWsSession(config, graph.getGovernanceGateway(),
                    graph.skillRegistry, graph.mcpManager,
                    graph.llmModule != null ? graph.llmModule.getDeepSeekAdapter() : null,
                    config);
            session.start();
            LOG.info("WebSocket 客户端已启动: " + config.getWebsocket().getUrl());
            return session;
        } catch (Exception e) {
            LOG.warning("WebSocket 客户端启动失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== 公共启动步骤 ====================

    /**
     * 初始化工作区元数据（workspaces.properties）。
     * <p>仅当文件不存在时写入，避免每次启动生成新的随机 wsId 并丢失既有工作区映射。</p>
     */
    private static void autoSetupWorkDir(AppConfig config) {
        Path wsFile = Paths.get(Constants.WORKSPACES_FILE);
        if (FileUtil.exists(wsFile)) {
            return;
        }
        String workspaceDir = config.getAuth().getWorkspaceDir();
        if (workspaceDir == null || workspaceDir.isBlank()) {
            workspaceDir = System.getProperty("user.dir");
        }
        String projectType = detectProjectType(Paths.get(workspaceDir));
        String wsId = "ws-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        FileUtil.createDirectories(wsFile.getParent());
        FileUtil.writeString(wsFile, wsId + "=" + Paths.get(workspaceDir).toAbsolutePath() + "|" + projectType + "|\n");
    }

    private static String detectProjectType(Path dir) {
        if (FileUtil.exists(dir.resolve("pom.xml"))) return "maven";
        if (FileUtil.exists(dir.resolve("build.gradle")) || FileUtil.exists(dir.resolve("settings.gradle"))) return "gradle";
        if (FileUtil.exists(dir.resolve("package.json"))) return "npm";
        if (FileUtil.exists(dir.resolve("go.mod"))) return "go";
        if (FileUtil.exists(dir.resolve("Cargo.toml"))) return "rust";
        if (FileUtil.exists(dir.resolve("requirements.txt")) || FileUtil.exists(dir.resolve("setup.py"))) return "python";
        return "unknown";
    }

    /**
     * 保留的旧版入口，防止直接调用 AgentMain --standalone 的用户断掉。
     */
    private static class OldAgentMain {
        static void main(String[] args) {
            try {
                var clazz = Class.forName("com.ccagent.presentation.cli.AgentMainLegacy");
                var method = clazz.getMethod("main", String[].class);
                method.invoke(null, (Object) args);
            } catch (Exception e) {
                System.err.println("旧版入口不可用，请使用默认 CLI 模式 (不加参数)");
            }
        }
    }
}
