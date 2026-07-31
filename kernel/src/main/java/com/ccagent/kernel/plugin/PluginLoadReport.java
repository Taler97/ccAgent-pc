package com.ccagent.kernel.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件加载报告
 * <p>汇总启动时的插件扫描、加载、失败情况，供启动日志输出和运维排查。</p>
 */
public class PluginLoadReport {

    private final int totalDescriptors;
    private final Map<PluginType, List<String>> loadedByType = new LinkedHashMap<>();
    private final List<FailedRecord> failedRecords = new ArrayList<>();

    public PluginLoadReport(int totalDescriptors) {
        this.totalDescriptors = totalDescriptors;
        for (PluginType type : PluginType.values()) {
            loadedByType.put(type, new ArrayList<>());
        }
    }

    public void recordLoaded(PluginDescriptor descriptor) {
        loadedByType.get(descriptor.getType()).add(descriptor.getName());
    }

    public void recordFailed(PluginDescriptor descriptor, String reason) {
        failedRecords.add(new FailedRecord(descriptor.getName(), descriptor.getType(), reason));
    }

    public int getTotalDescriptors() {
        return totalDescriptors;
    }

    public int getLoadedCount() {
        return loadedByType.values().stream().mapToInt(List::size).sum();
    }

    public int getFailedCount() {
        return failedRecords.size();
    }

    public List<String> getLoaded(PluginType type) {
        return Collections.unmodifiableList(loadedByType.get(type));
    }

    public List<FailedRecord> getFailedRecords() {
        return Collections.unmodifiableList(failedRecords);
    }

    /**
     * 生成人类可读的报告文本
     */
    public String toReportText() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append("║              插件加载报告                                ║\n");
        sb.append("╠══════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  扫描描述符: %-3d                                        ║%n", totalDescriptors));
        sb.append(String.format("║  加载成功:  %-3d                                        ║%n", getLoadedCount()));
        sb.append(String.format("║  加载失败:  %-3d                                        ║%n", getFailedCount()));
        sb.append("╠══════════════════════════════════════════════════════════╣\n");

        for (PluginType type : PluginType.values()) {
            List<String> loaded = loadedByType.get(type);
            if (!loaded.isEmpty()) {
                sb.append(String.format("║  [%s] (%d)%n", type, loaded.size()));
                for (String name : loaded) {
                    sb.append(String.format("║    + %s%n", name));
                }
            }
        }

        if (!failedRecords.isEmpty()) {
            sb.append("╠══════════════════════════════════════════════════════════╣\n");
            sb.append("║  失败详情                                               ║\n");
            for (FailedRecord record : failedRecords) {
                sb.append(String.format("║    - %s [%s]: %s%n",
                        record.name(), record.type(), record.reason()));
            }
        }

        sb.append("╚══════════════════════════════════════════════════════════╝");
        return sb.toString();
    }

    public record FailedRecord(String name, PluginType type, String reason) {}
}
