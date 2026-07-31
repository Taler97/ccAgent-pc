package com.ccagent.plugin.experts.impl;

import com.ccagent.core.expert.annotation.ExpertAnnotation;
import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.infrastructure.util.JsonUtil;

import java.util.Map;

/**
 * 规划专家
 * <p>负责将用户任务描述分解为可执行的计划（Plan JSON）。</p>
 * <p>可调用只读工具（读文件、搜索、列目录）来了解项目结构后再规划。</p>
 * <p>强制输出 Plan JSON 格式，不输出自然语言。</p>
 */
@ExpertAnnotation(
        name = "planner",
        description = "专业的任务规划师，负责将用户需求分解为详细的执行计划",
        category = "planning",
        tools = {"read_file", "search_content", "list_directory"}
)
public class PlannerExpert extends BaseExpert {

    public PlannerExpert() {}

    public PlannerExpert(ToolRegistryPort toolRegistry, LlmPort llmPort) {
        super(toolRegistry, llmPort);
    }

    @Override
    public String execute(String task, TaskScope taskScope, Map<String, Object> inputs,
                          String workspaceRoot, StreamObserver<StreamChunk> observer) {
        pushStatus(observer, "planning", "正在分析任务并生成执行计划...");

        String systemPrompt = buildPlanningSystemPrompt();
        String userPrompt = buildUserPrompt(task);

        String result = runReActLoop(systemPrompt, userPrompt, workspaceRoot, observer);

        String planJson = extractJson(result);
        String validated = validateAndFixPlan(planJson);
        if (validated == null) {
            pushStatus(observer, "error", "规划失败，无法生成有效的执行计划");
            return "{\"summary\":\"规划失败\",\"steps\":[]}";
        }

        pushStatus(observer, "completed", "执行计划生成完成");
        return validated;
    }

    @Override
    public String getName() {
        return "planner";
    }

    @Override
    public String getDescription() {
        return "专业的任务规划师，负责将用户需求分解为详细的执行计划";
    }

    private String buildPlanningSystemPrompt() {
        return ""
                + "你是一个专业的任务规划师。你的职责是将用户的任务描述分解为一个详细的可执行计划。\n\n"
                + "你可以调用以下只读工具来了解项目现状：\n"
                + "- read_file: 读取文件内容，了解现有代码\n"
                + "- search_content: 搜索文件内容，定位相关代码\n"
                + "- list_directory: 列出目录内容，了解项目结构\n\n"
                + "请先探索项目现状，再生成精准的 Plan。\n\n"
                + "## 输出格式（必须输出 Plan JSON）\n"
                + "```json\n"
                + "{\n"
                + "  \"summary\": \"任务概要描述\",\n"
                + "  \"estimatedImpact\": [\"受影响文件路径1\", \"受影响文件路径2\"],\n"
                + "  \"steps\": [\n"
                + "    {\n"
                + "      \"id\": 1,\n"
                + "      \"expertName\": \"writer\",\n"
                + "      \"task\": \"具体的任务描述，包含文件路径和操作说明\",\n"
                + "      \"affectedFiles\": [\"文件路径\"],\n"
                + "      \"reads\": [\"analysis\"],\n"
                + "      \"writes\": [\"code\"],\n"
                + "      \"persistOutput\": true\n"
                + "    }\n"
                + "  ]\n"
                + "}\n"
                + "```\n\n"
                + "## 专家列表\n"
                + "- writer: 撰写代码和文字内容，产出 writes[\"code\"]\n"
                + "- researcher: 研究分析，产出 writes[\"analysis\"]\n"
                + "- organizer: 整理文件，产出 writes[\"organization\"]\n"
                + "- note-taker: 记录笔记，产出 writes[\"notes\"]\n"
                + "- summarizer: 提炼摘要，产出 writes[\"summary\"]\n\n"
                + "## 规则\n"
                + "1. 每个 Step 必须指定 expertName、task、affectedFiles\n"
                + "2. 根据任务依赖关系设置 reads（需要读取的黑板 Key）和 writes（写入的黑板 Key）\n"
                + "3. 只输出 Plan JSON，不要输出其他内容\n"
                + "4. 如果任务很简单（如只读一个文件），生成单 Step 的 Plan\n"
                + "5. 复杂任务需要依赖关系时按顺序排列 Step";
    }

    private String buildUserPrompt(String task) {
        return "请为以下任务生成执行计划：\n\n" + task;
    }

    /**
     * 验证 Plan JSON 格式，若有问题尝试修复
     *
     * @param json 原始 Plan JSON 字符串
     * @return 修复后的 JSON，null 表示无法修复
     */
    private String validateAndFixPlan(String json) {
        if (json == null || json.isBlank()) return null;

        try {
            Plan plan = JsonUtil.fromJson(json, Plan.class);
            if (plan != null && plan.getSteps() != null && !plan.getSteps().isEmpty()) {
                return json;
            }
            if (plan != null) {
                return json;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}