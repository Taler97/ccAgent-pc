package com.ccagent.api.model.memory;

/**
 * 用户偏好
 * <p>L3 用户偏好：技术栈、代码风格、约束条件。</p>
 */
public class UserPreferences {

    private String techStack;
    private String codeStyle;
    private String constraints;

    public UserPreferences() {}

    public UserPreferences(String techStack, String codeStyle, String constraints) {
        this.techStack = techStack;
        this.codeStyle = codeStyle;
        this.constraints = constraints;
    }

    public String getTechStack() { return techStack; }
    public void setTechStack(String techStack) { this.techStack = techStack; }
    public String getCodeStyle() { return codeStyle; }
    public void setCodeStyle(String codeStyle) { this.codeStyle = codeStyle; }
    public String getConstraints() { return constraints; }
    public void setConstraints(String constraints) { this.constraints = constraints; }
}
