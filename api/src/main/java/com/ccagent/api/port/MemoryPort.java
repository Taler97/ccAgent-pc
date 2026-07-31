package com.ccagent.api.port;

import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.memory.StructuredContext;
import com.ccagent.api.model.memory.UserPreferences;
import com.ccagent.exception.workspace.WorkspaceException;

import java.util.List;

/**
 * 记忆管理端口
 * <p>管理对话记忆的持久化、查询、压缩和恢复。
 * 由 adapter/memory/ 实现。</p>
 */
public interface MemoryPort {

    /**
     * 加载会话的结构化记忆（摘要 + 最近轮次）
     *
     * @param sessionId 会话标识
     * @return 结构化记忆
     * @throws WorkspaceException 会话不存在
     */
    StructuredContext loadStructuredContext(String sessionId) throws WorkspaceException;

    /**
     * 保存一条对话轮次
     *
     * @param sessionId 会话标识
     * @param turn      对话轮次
     * @throws WorkspaceException 会话不存在
     */
    void saveTurn(String sessionId, ConversationTurn turn) throws WorkspaceException;

    /**
     * 手动触发记忆压缩
     *
     * @param sessionId 会话标识
     * @throws WorkspaceException 会话不存在
     */
    void compressContext(String sessionId) throws WorkspaceException;

    /**
     * 获取最近 N 轮对话
     *
     * @param sessionId 会话标识
     * @param limit     获取轮次数
     * @return 最近的对话轮次列表
     * @throws WorkspaceException 会话不存在
     */
    List<ConversationTurn> getRecentTurns(String sessionId, int limit) throws WorkspaceException;

    /**
     * 获取会话总轮次数
     *
     * @param sessionId 会话标识
     * @return 总轮次数
     * @throws WorkspaceException 会话不存在
     */
    int getTotalTurns(String sessionId) throws WorkspaceException;

    /**
     * 加载用户偏好
     *
     * @param workspaceId 工作区标识
     * @return 用户偏好
     */
    UserPreferences loadPreferences(String workspaceId);

    /**
     * 保存用户偏好
     *
     * @param workspaceId 工作区标识
     * @param preferences 用户偏好
     */
    void savePreferences(String workspaceId, UserPreferences preferences);

    /**
     * 创建新会话
     *
     * @param sessionId   会话标识
     * @param workspaceId 工作区标识
     * @param rootPath    工作区根目录绝对路径（安全围栏边界）
     * @throws WorkspaceException 创建失败
     */
    void createSession(String sessionId, String workspaceId, String rootPath) throws WorkspaceException;

    /**
     * 获取会话绑定的工作区路径
     *
     * @param sessionId 会话标识
     * @return 工作区根路径（绝对路径），会话不存在时抛异常
     * @throws WorkspaceException 会话不存在或工作区配置丢失
     */
    String loadWorkspaceRoot(String sessionId) throws WorkspaceException;

    /**
     * 获取所有会话列表（按最后更新时间降序）
     */
    List<SessionInfo> listSessions();
}
