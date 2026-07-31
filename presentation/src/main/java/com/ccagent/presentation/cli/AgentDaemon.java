package com.ccagent.presentation.cli;

import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.infrastructure.config.ConfigLoader;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.logging.LogConfig;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.presentation.ws.CloudWebSocketClient;
import com.ccagent.presentation.ws.MessageRouter;
import com.ccagent.presentation.ws.ReconnectManager;
import com.ccagent.presentation.ws.StateManager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * PC Agent 守护进程入口（纯引擎，无 HTTP）
 * <p>启动引擎 + WebSocket 客户端（可选），以后台方式运行。
 * 不启动 HTTP 服务，前端需通过 Cloud Server 或独立 CLI 交互。</p>
 *
 * <p>启动方式：</p>
 * <pre>
 *   java com.ccagent.presentation.cli.AgentDaemon
 * </pre>
 */
public class AgentDaemon {

    private static final Logger LOG = Logger.getLogger(AgentDaemon.class.getName());
    private static volatile boolean running = true;

    public static void main(String[] args) {
        try {
            AppConfig config = ConfigLoader.load();
            autoSetupWorkDir(config);
            LogConfig.init();

            DependencyGraph graph = new DependencyGraph();
            graph.assemble(config);

            // 启动 WebSocket 客户端，连接 Cloud Server（可选）
            CloudWsConnection wsConn = startCloudWsClient(config, graph);

            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║     ccAgent 守护进程已启动               ║");
            System.out.println("║  引擎就绪，等待任务调度                    ║");
            if (wsConn != null) {
                System.out.println("║  Cloud: " + config.getWebsocket().getUrl() + "          ║");
            }
            System.out.println("║  输入 /exit 停止                          ║");
            System.out.println("╚══════════════════════════════════════════╝");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running = false;
                if (wsConn != null) wsConn.shutdown();
                graph.shutdown();
                System.out.println("\nccAgent 守护进程已停止");
            }));

            // 监听控制台输入，支持 /exit 优雅停止
            Thread consoleListener = new Thread(() -> {
                try (java.util.Scanner scanner = new java.util.Scanner(System.in)) {
                    while (running && scanner.hasNextLine()) {
                        String line = scanner.nextLine().trim();
                        if ("/exit".equalsIgnoreCase(line)) {
                            System.out.println("正在停止守护进程...");
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
            System.err.println("守护进程启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 启动 WebSocket 客户端（可选），连接 Cloud Server 接受远程调度。
     */
    private static CloudWsConnection startCloudWsClient(AppConfig config, DependencyGraph graph) {
        if (config.getWebsocket() == null || config.getWebsocket().getUrl() == null
                || config.getWebsocket().getUrl().isBlank()) {
            return null;
        }
        try {
            MessageRouter router = new MessageRouter(graph.getGovernanceGateway());
            StateManager stateManager = new StateManager();
            ReconnectManager reconnectManager = new ReconnectManager(
                    config.getWebsocket().getReconnectInitialDelay(),
                    config.getWebsocket().getReconnectMaxDelay()
            );
            CloudWebSocketClient wsClient = new CloudWebSocketClient(
                    config.getWebsocket().getUrl(), router, stateManager, reconnectManager
            );
            wsClient.start();
            LOG.info("WebSocket 客户端已启动: " + config.getWebsocket().getUrl());
            return new CloudWsConnection(wsClient);
        } catch (Exception e) {
            LOG.warning("WebSocket 客户端启动失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 持有 WS 客户端引用的轻量封装，用于优雅关闭。
     */
    private record CloudWsConnection(CloudWebSocketClient client) {
        void shutdown() {
            try { client.shutdown(); } catch (Exception ignored) {}
        }
    }

    private static void autoSetupWorkDir(AppConfig config) {
        Path wsFile = Paths.get(Constants.WORKSPACES_FILE);
        String workspaceDir = config.getAuth().getWorkspaceDir();
        if (workspaceDir != null && !workspaceDir.isEmpty()) {
            String projectType = detectProjectType(Paths.get(workspaceDir));
            String wsId = "ws-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            FileUtil.createDirectories(wsFile.getParent());
            FileUtil.writeString(wsFile, wsId + "=" + Paths.get(workspaceDir).toAbsolutePath() + "|" + projectType + "|\n");
        } else if (!FileUtil.exists(wsFile)) {
            String cwd = System.getProperty("user.dir");
            String projectType = detectProjectType(Paths.get(cwd));
            String wsId = "ws-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            FileUtil.createDirectories(wsFile.getParent());
            FileUtil.writeString(wsFile, wsId + "=" + Paths.get(cwd).toAbsolutePath() + "|" + projectType + "|\n");
        }
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
}
