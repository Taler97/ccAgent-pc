package com.ccagent.presentation.cli;

import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.infrastructure.config.ConfigLoader;
import com.ccagent.infrastructure.logging.LogConfig;

/**
 * ccAgent 统一入口
 *
 * <h3>启动模式</h3>
 * <ul>
 *   <li><b>默认（无参数）</b> — 本地 CLI 交互模式，引擎嵌入进程，直接对话</li>
 *   <li><b>--headless</b> — 守护进程模式（无 HTTP），引擎后台运行，等待任务调度</li>
 *   <li><b>--standalone</b> — （已废弃）旧版单进程模式，建议使用默认 CLI 模式</li>
 * </ul>
 */
public class AgentMain {

    public static void main(String[] args) {
        boolean headless = false;
        boolean standalone = false;

        for (String arg : args) {
            switch (arg) {
                case "--headless" -> headless = true;
                case "--standalone" -> { standalone = true; headless = false; }
            }
        }

        if (headless) {
            // 守护进程模式 — 引擎后台运行，无 HTTP，等待 WebSocket 任务调度
            AgentDaemon.main(args);

        } else if (standalone) {
            // 保留旧版单进程模式（兼容）
            OldAgentMain.main(args);

        } else {
            // 默认：本地 CLI 交互模式
            runLocalCli();
        }
    }

    /**
     * 本地 CLI 交互模式：引擎嵌入进程，直接对话。
     */
    private static void runLocalCli() {
        try {
            AppConfig config = ConfigLoader.load();
            LogConfig.init();

            DependencyGraph graph = new DependencyGraph();
            graph.assemble(config);

            String workspaceDir = config.getAuth().getWorkspaceDir();
            if (workspaceDir == null || workspaceDir.isBlank()) {
                workspaceDir = ".";
            }

            StandaloneAgentRunner runner = new StandaloneAgentRunner(
                    graph.getGovernanceGateway(),
                    config,
                    graph.getSyncScheduler(),
                    graph.getLocalDataRepository(),
                    graph.getExpertRegistry()
            );
            runner.start();

            graph.shutdown();

        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
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
