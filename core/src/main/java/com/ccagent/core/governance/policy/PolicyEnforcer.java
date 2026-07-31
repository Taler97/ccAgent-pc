package com.ccagent.core.governance.policy;

import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.security.SecurityException;
import com.ccagent.exception.workspace.WorkspaceException;
import com.ccagent.infrastructure.util.PathUtil;

/**
 * 策略执行器
 * <p>纯函数式设计——无状态、无副作用、无 IO。
 * 检查配额、路径白名单、工作区权限。</p>
 */
public class PolicyEnforcer {

    /**
     * 检查工作区是否存在
     *
     * @param workspaceId 工作区标识
     * @throws WorkspaceException 工作区不存在
     */
    public void checkWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND,
                    "工作区标识不能为空");
        }
        // TODO: 从 workspaces.properties 验证 workspaceId 是否存在
    }

    /**
     * 检查路径是否在工作区内（防路径穿越）
     *
     * @param workspaceRoot 工作区根路径
     * @param requestedPath 请求的路径
     * @throws SecurityException 路径超出工作区范围
     */
    public void checkPath(String workspaceRoot, String requestedPath) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            throw new SecurityException(ErrorCode.PATH_TRAVERSAL_DETECTED,
                    "工作区根路径未配置", requestedPath, workspaceRoot);
        }
        if (requestedPath == null || requestedPath.isBlank()) {
            return; // 空路径不做检查
        }
        if (!PathUtil.isSubPath(workspaceRoot, requestedPath)) {
            throw new SecurityException(ErrorCode.PATH_TRAVERSAL_DETECTED,
                    "路径超出工作区范围: " + requestedPath, requestedPath, workspaceRoot);
        }
    }
}
