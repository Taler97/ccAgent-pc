package com.ccagent.presentation.ws;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.infrastructure.config.ConfigLoader;
import com.ccagent.infrastructure.util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class CloudWebSocketClient {

    private static final Logger LOG = Logger.getLogger(CloudWebSocketClient.class.getName());

    private static final int PING_INTERVAL_SECONDS = 30;
    private static final int PONG_TIMEOUT_SECONDS = 60;

    private final String baseUrl;
    private final MessageRouter router;
    private final StateManager stateManager;
    private final ReconnectManager reconnectManager;
    private final HttpClient httpClient;
    private final String deviceId;

    private volatile WebSocket webSocket;
    private volatile boolean running = false;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile Thread pingThread;
    private volatile long lastPongTimestamp = System.currentTimeMillis();

    public CloudWebSocketClient(String baseUrl, MessageRouter router, StateManager stateManager,
                                 ReconnectManager reconnectManager) {
        this.baseUrl = baseUrl;
        this.router = router;
        this.stateManager = stateManager;
        this.reconnectManager = reconnectManager;
        this.httpClient = HttpClient.newHttpClient();
        this.deviceId = "pc-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        reconnectManager.reset();
        connect();
    }

    public synchronized void stop() {
        running = false;
        stopPingThread();
        reconnectManager.stop();
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
            webSocket = null;
        }
    }

    public void shutdown() {
        stop();
    }

    private String buildWsUrl() {
        String base = baseUrl;
        if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
            base = "ws://" + base;
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String apiKey = ConfigLoader.getConfig().getAuth().getApiKey();
        return base + "/pc?deviceId=" + deviceId + "&api_key=" + apiKey;
    }

    private MessageRouter.MessageSender createSender() {
        return (Envelope envelope) -> {
            WebSocket ws = webSocket;
            if (ws != null) {
                try {
                    ws.sendText(JsonUtil.toJson(envelope), true);
                } catch (Exception e) {
                    LOG.warning("发送消息失败: " + e.getMessage());
                }
            }
        };
    }

    private void startPingThread(WebSocket ws) {
        stopPingThread();
        pingThread = Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(PING_INTERVAL_SECONDS * 1000L);
                while (running && ws == webSocket) {
                    if (ws.isInputClosed()) break;
                    ws.sendPing(ByteBuffer.allocate(0));
                    long elapsed = System.currentTimeMillis() - lastPongTimestamp;
                    if (elapsed > PONG_TIMEOUT_SECONDS * 1000L) {
                        LOG.warning("心跳超时（" + elapsed / 1000 + "s 无 Pong），触发重连");
                        reconnectManager.start(CloudWebSocketClient.this::connect);
                        break;
                    }
                    Thread.sleep(PING_INTERVAL_SECONDS * 1000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void stopPingThread() {
        Thread t = pingThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
            pingThread = null;
        }
    }

    private void connect() {
        if (!running || !connecting.compareAndSet(false, true)) return;

        String url = buildWsUrl();
        LOG.info("正在连接: " + url);

        try {
            httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() {

                        private MessageRouter.MessageSender sender;

                        @Override
                        public void onOpen(WebSocket ws) {
                            webSocket = ws;
                            sender = createSender();
                            connecting.set(false);
                            reconnectManager.reset();
                            lastPongTimestamp = System.currentTimeMillis();
                            stateManager.setCloudState(StateManager.CloudState.CONNECTED);

                            startPingThread(ws);

                            LOG.info("已连接云端");
                            ws.request(1);
                        }

                        private final StringBuilder messageBuffer = new StringBuilder();

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            messageBuffer.append(data);
                            if (last) {
                                String fullMessage = messageBuffer.toString();
                                messageBuffer.setLength(0);
                                try {
                                    router.route(fullMessage, sender);
                                } catch (Exception e) {
                                    LOG.warning("消息路由失败: " + e.getMessage());
                                }
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
                            lastPongTimestamp = System.currentTimeMillis();
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                            webSocket = null;
                            sender = null;
                            connecting.set(false);
                            stopPingThread();
                            stateManager.setCloudState(StateManager.CloudState.DISCONNECTED);
                            LOG.info("连接已关闭: " + statusCode + " " + reason);
                            if (running) reconnectManager.start(CloudWebSocketClient.this::connect);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            webSocket = null;
                            sender = null;
                            connecting.set(false);
                            stopPingThread();
                            stateManager.setCloudState(StateManager.CloudState.DISCONNECTED);
                            LOG.warning("连接错误: " + error.getMessage());
                            if (running) reconnectManager.start(CloudWebSocketClient.this::connect);
                        }
                    }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            connecting.set(false);
            LOG.warning("连接失败: " + e.getMessage());
            if (running) reconnectManager.start(this::connect);
        }
    }

    public void send(Envelope envelope) {
        WebSocket ws = webSocket;
        if (ws == null) return;
        try {
            ws.sendText(JsonUtil.toJson(envelope), true);
        } catch (Exception e) {
            LOG.warning("发送消息失败: " + e.getMessage());
        }
    }
}
