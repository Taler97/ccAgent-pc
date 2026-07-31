package com.ccagent.core.expert;

import com.ccagent.api.port.Expert;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认专家注册表
 * <p>基于名称的专家查找与注册。</p>
 */
public class DefaultExpertRegistry implements ExpertRegistryPort {

    private final Map<String, Expert> experts = new ConcurrentHashMap<>();

    public void register(Expert expert) {
        if (expert != null && expert.getName() != null) {
            experts.put(expert.getName(), expert);
        }
    }

    @Override
    public Expert getExpert(String expertName) throws ToolException {
        Expert expert = experts.get(expertName);
        if (expert == null) {
            throw new ToolException(ErrorCode.EXPERT_NOT_FOUND, "专家不存在: " + expertName);
        }
        return expert;
    }

    @Override
    public List<String> listExperts() {
        List<String> names = new ArrayList<>(experts.keySet());
        Collections.sort(names);
        return names;
    }
}
