package com.ccagent.api.port;

import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.response.StreamChunk;

import java.util.Map;

/**
 * 专家接口
 * <p>定义在 api/ 层，由适配层实现，供编排层调用。
 * 每个专家负责一个特定领域的代码任务。</p>
 *
 * <h3>缩略型黑板 (LangGraph 风格)</h3>
 * <p>专家返回 {@code Map<String, Object>} 作为缩略结构化产物, 而非完整字符串.
 * Map 的每个 Key 对应 blackboard-schema.yaml 中定义的字段, value 是该字段的增量值.
 * Dispatcher 会按 schema 的 mergeStrategy (append/replace/merge) 合并进黑板.</p>
 */
public interface Expert {

    /**
     * 执行专家任务（旧接口，不支持黑板）
     *
     * @param task          自然语言任务描述
     * @param workspaceRoot 工作区根路径（用于工具调用路径解析）
     * @param observer      结果回调（流式推送 StreamChunk）
     */
    @Deprecated
    void execute(String task, String workspaceRoot, StreamObserver<StreamChunk> observer);

    /**
     * 执行专家任务（黑板模式）
     * <p>通过 TaskScope 消费/生产黑板数据，inputs 为预取的依赖产物。
     * 专家应消费 inputs 并返回缩略结构化产物 Map, 每个 Key 对应 schema 字段.</p>
     *
     * @param task          自然语言任务描述
     * @param taskScope     任务级黑板上下文（可读写 Artifact）
     * @param inputs        预取的依赖数据（reads Key → value）
     * @param workspaceRoot 工作区根路径
     * @param observer      结果回调（流式推送 StreamChunk）
     * @return 缩略结构化产物 Map (Key → 增量值), Dispatcher 按 schema mergeStrategy 合并
     */
    Map<String, Object> execute(String task, TaskScope taskScope, Map<String, Object> inputs,
                                String workspaceRoot, StreamObserver<StreamChunk> observer);

    /**
     * @return 专家名称（唯一标识）
     */
    String getName();

    /**
     * @return 专家能力描述（供规划阶段 LLM 理解）
     */
    String getDescription();
}
