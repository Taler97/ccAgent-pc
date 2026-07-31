package com.ccagent.core.sync;

import com.ccagent.core.sync.model.SyncDiff;
import com.ccagent.core.sync.model.SyncManifest;
import com.ccagent.infrastructure.util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class SyncClient {

    private final String cloudUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public SyncClient(String cloudUrl, String apiKey) {
        this.cloudUrl = cloudUrl.endsWith("/") ? cloudUrl.substring(0, cloudUrl.length() - 1) : cloudUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isCloudReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cloudUrl + "/api/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public SyncDiff compareManifest(List<SyncManifest> localManifest) {
        try {
            List<Map<String, Object>> manifestList = new ArrayList<>();
            for (SyncManifest m : localManifest) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("entityType", m.getEntityType());
                entry.put("entityId", m.getEntityId());
                entry.put("version", m.getVersion());
                manifestList.add(entry);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("manifest", manifestList);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cloudUrl + "/api/sync/compare"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return JsonUtil.fromJson(response.body(), SyncDiff.class);
            }
        } catch (Exception ignored) {}
        return new SyncDiff();
    }

    public boolean uploadEntity(String entityType, String entityId, int version,
                                 String data, String checksum) {
        try {
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("entityType", entityType);
            entity.put("entityId", entityId);
            entity.put("version", version);
            entity.put("data", data);
            entity.put("checksum", checksum);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("entities", List.of(entity));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cloudUrl + "/api/sync/upload"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public String downloadEntity(String entityType, String entityId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cloudUrl + "/api/sync/download?type=" + entityType + "&id=" + entityId))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
