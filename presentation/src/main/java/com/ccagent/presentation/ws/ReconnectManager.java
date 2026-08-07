package com.ccagent.presentation.ws;

/**
 * 重连管理器
 * <p>指数退避重连策略。</p>
 */
public class ReconnectManager {

    private final int initialDelaySeconds;
    private final int maxDelaySeconds;
    private int attempt = 0;
    private volatile boolean running = false;
    private Thread reconnectThread;

    public ReconnectManager(int initialDelaySeconds, int maxDelaySeconds) {
        this.initialDelaySeconds = initialDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
    }

    /**
     * 开始重连（异步）。
     * <p>若已有重连线程在运行则不重复启动；连接成功后应调用 {@link #stop()} 停止循环，
     * 断开/失败时再调用本方法重新启动。</p>
     */
    public synchronized void start(Runnable reconnectAction) {
        if (running) return;
        running = true;
        attempt = 0;

        reconnectThread = new Thread(() -> {
            while (running) {
                int delay = getNextDelay();
                try {
                    Thread.sleep(delay * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (running) {
                    reconnectAction.run();
                    attempt++;
                }
            }
        }, "reconnect-manager");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * 获取下次重连延迟（指数退避，封顶）
     */
    private int getNextDelay() {
        int delay = initialDelaySeconds * (int) Math.pow(2, attempt);
        return Math.min(delay, maxDelaySeconds);
    }

    /**
     * 重置重连计数（连接成功后调用）
     */
    public synchronized void reset() {
        attempt = 0;
    }

    /**
     * 停止重连
     */
    public synchronized void stop() {
        running = false;
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }
    }
}
