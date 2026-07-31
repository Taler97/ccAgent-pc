package com.ccagent.infrastructure.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.*;

/**
 * 系统日志初始化
 * <p>在 AgentMain 启动时调用一次，配置 java.util.logging 输出到文件和控制台。
 * 仅管理系统日志（{@code logs/agent.log}），审计日志由治理层 Auditor 独立管理。</p>
 */
public final class LogConfig {

    private static final String LOG_PATTERN = "[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%2$s] [%3$s] %4$s%n";
    private static volatile boolean initialized = false;

    private LogConfig() {}

    /**
     * 初始化系统日志
     * <p>创建日志目录，配置 FileHandler（轮转）+ ConsoleHandler。</p>
     *
     * @throws RuntimeException 若日志初始化失败
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }


        String logFile = "G:\\ccAgent\\logs\\agent.log";

        try {
            // 确保日志目录存在
            Files.createDirectories(Paths.get(logFile));

            Logger rootLogger = Logger.getLogger("");
            rootLogger.setLevel(Level.INFO);

            // 移除默认的 ConsoleHandler
            for (Handler handler : rootLogger.getHandlers()) {
                rootLogger.removeHandler(handler);
            }

            // 文件处理器：10MB 轮转，保留 5 个
            FileHandler fileHandler = new FileHandler(logFile, 10_000_000, 5, true);
            fileHandler.setLevel(Level.INFO);
            fileHandler.setFormatter(new SimpleFormatter() {
                @Override
                public synchronized String format(LogRecord record) {
                    return String.format(LOG_PATTERN,
                            record.getMillis(),
                            record.getLevel().getName(),
                            record.getLoggerName(),
                            record.getMessage());
                }
            });
            rootLogger.addHandler(fileHandler);

            // 控制台处理器
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.INFO);
            consoleHandler.setFormatter(new SimpleFormatter() {
                @Override
                public synchronized String format(LogRecord record) {
                    return String.format(LOG_PATTERN,
                            record.getMillis(),
                            record.getLevel().getName(),
                            record.getLoggerName(),
                            record.getMessage());
                }
            });
            rootLogger.addHandler(consoleHandler);

            initialized = true;

            Logger logger = Logger.getLogger(LogConfig.class.getName());
            logger.info("系统日志初始化完成: " + logFile);

        } catch (IOException e) {
            throw new RuntimeException("日志初始化失败: " + logFile, e);
        }
    }
}
