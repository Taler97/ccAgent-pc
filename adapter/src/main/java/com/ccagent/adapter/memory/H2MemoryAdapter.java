package com.ccagent.adapter.memory;

import com.ccagent.adapter.memory.repository.PreferencesRepository;
import com.ccagent.adapter.memory.repository.SessionRepository;
import com.ccagent.adapter.memory.schema.DatabaseInitializer;
import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.memory.StructuredContext;
import com.ccagent.api.model.memory.UserPreferences;
import com.ccagent.api.port.MemoryPort;
import com.ccagent.api.port.LlmPort;
import com.ccagent.core.memory.Compressor;
import com.ccagent.exception.workspace.WorkspaceException;
import com.ccagent.infrastructure.util.JsonUtil;

import javax.sql.DataSource;
import java.util.List;

/**
 * H2 记忆适配器
 * <p>实现 MemoryPort，使用 H2 嵌入式数据库持久化记忆。</p>
 */
public class H2MemoryAdapter implements MemoryPort {

    private final SessionRepository sessionRepository;
    private final PreferencesRepository preferencesRepository;
    private final Compressor compressor;

    public H2MemoryAdapter(LlmPort llmPort) {
        DataSource dataSource = DatabaseInitializer.createDataSource();
        DatabaseInitializer initializer = new DatabaseInitializer(dataSource);
        initializer.initialize();

        this.sessionRepository = new SessionRepository(dataSource);
        this.preferencesRepository = new PreferencesRepository(dataSource);
        this.compressor = new Compressor(llmPort);
    }

    /**
     * 带外部 DataSource 的构造器（用于测试）
     */
    public H2MemoryAdapter(DataSource dataSource, LlmPort llmPort) {
        DatabaseInitializer initializer = new DatabaseInitializer(dataSource);
        initializer.initialize();

        this.sessionRepository = new SessionRepository(dataSource);
        this.preferencesRepository = new PreferencesRepository(dataSource);
        this.compressor = new Compressor(llmPort);
    }

    @Override
    public StructuredContext loadStructuredContext(String sessionId) {
        return sessionRepository.loadStructuredContext(sessionId);
    }

    @Override
    public void saveTurn(String sessionId, ConversationTurn turn) {
        StructuredContext ctx = sessionRepository.loadStructuredContext(sessionId);
        List<ConversationTurn> turns = ctx.getRecentTurns();
        turns.add(turn);
        int totalTurns = ctx.getTotalTurns() + 1;

        // 检查是否需要压缩
        if (totalTurns > 30) { // memory.max-turns
            try {
                Compressor.CompressResult result = compressor.compress(turns, ctx.getSummary());
                if (result != null) {
                    String remainingJson = JsonUtil.toJson(result.remaining());
                    sessionRepository.updateMemory(sessionId, result.summary(), remainingJson, result.totalTurns());
                    return;
                }
            } catch (Exception e) {
                // 压缩失败不阻塞，降级为直接保存
            }
        }

        String recentTurnsJson = JsonUtil.toJson(turns);
        sessionRepository.updateMemory(sessionId, ctx.getSummary(), recentTurnsJson, totalTurns);
    }

    @Override
    public void compressContext(String sessionId) {
        StructuredContext ctx = sessionRepository.loadStructuredContext(sessionId);
        Compressor.CompressResult result = compressor.compress(ctx.getRecentTurns(), ctx.getSummary());
        if (result != null) {
            String remainingJson = JsonUtil.toJson(result.remaining());
            sessionRepository.updateMemory(sessionId, result.summary(), remainingJson, result.totalTurns());
        }
    }

    @Override
    public List<ConversationTurn> getRecentTurns(String sessionId, int limit) {
        StructuredContext ctx = sessionRepository.loadStructuredContext(sessionId);
        List<ConversationTurn> turns = ctx.getRecentTurns();
        if (turns.size() > limit) {
            return turns.subList(turns.size() - limit, turns.size());
        }
        return turns;
    }

    @Override
    public int getTotalTurns(String sessionId) {
        return sessionRepository.getTotalTurns(sessionId);
    }

    @Override
    public UserPreferences loadPreferences(String workspaceId) {
        return preferencesRepository.loadPreferences();
    }

    @Override
    public void savePreferences(String workspaceId, UserPreferences preferences) {
        preferencesRepository.savePreferences(preferences);
    }

    @Override
    public void createSession(String sessionId, String workspaceId, String rootPath) {
        sessionRepository.createSession(sessionId, workspaceId, rootPath);
    }

    @Override
    public String loadWorkspaceRoot(String sessionId) {
        return sessionRepository.getRootPath(sessionId);
    }

    @Override
    public List<SessionInfo> listSessions() {
        return sessionRepository.listAllSessions();
    }
}
