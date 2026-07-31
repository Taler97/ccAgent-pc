package com.ccagent.api.model.plan;

import java.util.List;
import java.util.Set;

/**
 * 计划步骤
 * <p>Plan 中的一个有序执行步骤。通过 reads/writes 声明黑板 Key 依赖与产出。</p>
 */
public class Step {

    private int id;
    private String expertName;
    private String task;
    private List<String> affectedFiles;
    private Set<String> reads;
    private Set<String> writes;
    private boolean persistOutput;

    public Step() {}

    public Step(int id, String expertName, String task, List<String> affectedFiles) {
        this.id = id;
        this.expertName = expertName;
        this.task = task;
        this.affectedFiles = affectedFiles;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getExpertName() { return expertName; }
    public void setExpertName(String expertName) { this.expertName = expertName; }
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public List<String> getAffectedFiles() { return affectedFiles; }
    public void setAffectedFiles(List<String> affectedFiles) { this.affectedFiles = affectedFiles; }
    public Set<String> getReads() { return reads; }
    public void setReads(Set<String> reads) { this.reads = reads; }
    public Set<String> getWrites() { return writes; }
    public void setWrites(Set<String> writes) { this.writes = writes; }
    public boolean isPersistOutput() { return persistOutput; }
    public void setPersistOutput(boolean persistOutput) { this.persistOutput = persistOutput; }
}
