package com.ccagent.api.model.response;

import java.time.Instant;

/**
 * 文件节点
 * <p>文件列表浏览的返回结构。</p>
 */
public class FileNode {

    private String name;
    private boolean isDir;
    private long size;
    private Instant lastModified;

    public FileNode() {}

    public FileNode(String name, boolean isDir, long size, Instant lastModified) {
        this.name = name;
        this.isDir = isDir;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isDir() { return isDir; }
    public void setDir(boolean dir) { isDir = dir; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }
}
