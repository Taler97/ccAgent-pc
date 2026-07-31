package com.ccagent.api.port;

import com.ccagent.exception.tool.ToolException;

import java.util.List;

/**
 * 专家注册与查询端口
 * <p>注册所有可用专家，按名称查询专家实例。
 * 由 adapter/expert/ 实现。</p>
 */
public interface ExpertRegistryPort {

    /**
     * 获取专家实例
     *
     * @param expertName 专家名称
     * @return 专家实例
     * @throws ToolException 专家不存在
     */
    Expert getExpert(String expertName) throws ToolException;

    /**
     * 列出所有专家名称
     *
     * @return 专家名称列表（按名称字母序排列）
     */
    List<String> listExperts();
}
