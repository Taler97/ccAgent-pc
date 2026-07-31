package com.ccagent.api.port;

import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.exception.base.AgentException;

import java.util.List;

/**
 * 流程编排入口端口
 * <p>接收来自 presentation/ 层的指令，是整个业务核心层的唯一入口。
 * 由 governance/ 的 GovernanceGateway 实现。</p>
 */
public interface OrchestratorPort {

    /**
     * 执行用户指令
     *
     * @param request  执行请求
     * @param observer 结果回调（流式推送 StreamChunk）
     * @throws AgentException 业务异常
     */
    void execute(ExecuteRequest request, StreamObserver<StreamChunk> observer) throws AgentException;

    /**
     * 使用指定 seqId 执行用户指令
     *
     * @param request  执行请求
     * @param seqId    消息序列号（来自 H5）
     * @param observer 结果回调
     */
    void executeWithSeqId(ExecuteRequest request, String seqId, StreamObserver<StreamChunk> observer);

    /**
     * 取消正在执行的请求
     *
     * @param seqId 消息序列号
     * @return true 若取消成功，false 若无可取消的执行
     * @throws AgentException 业务异常
     */
    boolean cancelExecution(String seqId) throws AgentException;

    /**
     * 接收用户对工具审批的响应
     *
     * @param seqId    消息序列号
     * @param approved true 同意 / false 拒绝
     * @throws AgentException 业务异常
     */
    void onToolApproval(String seqId, boolean approved) throws AgentException;

    /**
     * 用户确认 Plan 后触发执行
     *
     * @param seqId     消息序列号
     * @param confirmed true 确认执行 / false 取消
     * @throws AgentException 业务异常
     */
    void executePlan(String seqId, boolean confirmed) throws AgentException;

    /**
     * 取消等待中的 Plan
     *
     * @param seqId 消息序列号
     * @return true 若取消成功
     * @throws AgentException 业务异常
     */
    boolean cancelPlan(String seqId) throws AgentException;

    /**
     * 回滚指定对话的所有文件变更
     *
     * @param seqId 消息序列号
     * @return true 若全部恢复成功
     */
    boolean rollbackDialog(String seqId);

    /**
     * 创建新会话并绑定工作区根目录
     *
     * @param sessionId   会话标识
     * @param rootPath    工作区根目录绝对路径（安全围栏边界）
     */
    void createSession(String sessionId, String rootPath);

    /**
     * 获取会话绑定的工作区根目录
     */
    String loadWorkspaceRoot(String sessionId);

    /**
     * 获取所有会话列表（按最后更新时间降序）
     */
    List<SessionInfo> listSessions();
}
