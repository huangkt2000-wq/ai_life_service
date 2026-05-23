package com.hmdp.ai.memory.ltm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.memory.ltm.config.QdrantProperties;
import com.hmdp.ai.memory.ltm.dto.MemoryArchiveItem;
import com.hmdp.ai.memory.ltm.dto.MemoryRecord;
import com.hmdp.ai.memory.ltm.dto.MemorySearchResult;
import com.hmdp.ai.memory.ltm.dto.VectorSearchQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class QdrantVectorStore implements VectorStore {

    private final QdrantProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public QdrantVectorStore(
            QdrantProperties properties,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        WebClient.Builder builder = webClientBuilder.baseUrl(properties.getEndpoint());
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.defaultHeader("api-key", properties.getApiKey());
        }
        this.webClient = builder.build();
    }

    @PostConstruct
    public void ensureCollection() {
        if (!StringUtils.hasText(properties.getCollectionName())) {
            return;
        }

        webClient.get()
                .uri("/collections/{collection}", properties.getCollectionName())
                .exchangeToMono(response -> handleCollectionCheck(response))
                .block();
    }

    // 如果 collection 不存在，则创建；如果存在，则直接返回
    private Mono<Void> handleCollectionCheck(ClientResponse response) {
        if (response.statusCode().equals(HttpStatus.NOT_FOUND)) {
            Map<String, Object> createBody = new HashMap<>();
            Map<String, Object> vectors = new HashMap<>();
            vectors.put("size", properties.getVectorSize());
            vectors.put("distance", properties.getDistance());
            createBody.put("vectors", vectors);

            return webClient.put()
                    .uri("/collections/{collection}", properties.getCollectionName())
                    .bodyValue(createBody)
                    .retrieve()
                    .bodyToMono(Void.class);
        }

        return response.bodyToMono(Void.class);
    }

    // 用于批量插入或更新向量数据
    @Override
    public List<String> upsert(List<MemoryRecord> records) {
        if (CollectionUtils.isEmpty(records)) {
            return List.of();
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (MemoryRecord record : records) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", record.getMemoryId());
            point.put("vector", record.getVector());
            point.put("payload", buildPayload(record));
            points.add(point);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("points", points);

        webClient.post()
                .uri("/collections/{collection}/points?wait=true", properties.getCollectionName())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        List<String> ids = new ArrayList<>(records.size());
        for (MemoryRecord record : records) {
            ids.add(record.getMemoryId());
        }
        return ids;
    }

    @Override
    public List<MemorySearchResult> query(VectorSearchQuery query) {
        if (query == null || CollectionUtils.isEmpty(query.getVector())) {
            return List.of();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("vector", query.getVector());
        body.put("limit", query.getTopK() != null ? query.getTopK() : 5);
        body.put("with_payload", true);

        Map<String, Object> filter = buildFilter(query);
        if (filter != null) {
            body.put("filter", filter);
        }

        String responseJson = webClient.post()
                .uri("/collections/{collection}/points/search", properties.getCollectionName())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (responseJson == null || responseJson.isBlank()) {
            return List.of();
        }

        return parseSearchResults(responseJson);
    }

    @Override
    public boolean delete(List<String> memoryIds) {
        if (CollectionUtils.isEmpty(memoryIds)) {
            return false;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("points", memoryIds);

        webClient.post()
                .uri("/collections/{collection}/points/delete?wait=true", properties.getCollectionName())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return true;
    }

    @Override
    public boolean deleteByUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return false;
        }

        Map<String, Object> filter = new HashMap<>();
        filter.put("must", List.of(matchFilter("user_id", userId)));

        Map<String, Object> body = new HashMap<>();
        body.put("filter", filter);

        webClient.post()
                .uri("/collections/{collection}/points/delete?wait=true", properties.getCollectionName())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return true;
    }

    private Map<String, Object> buildPayload(MemoryRecord record) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("user_id", record.getUserId());
        payload.put("session_id", record.getSessionId());
        payload.put("text", record.getText());
        payload.put("metadata", record.getMetadata());
        payload.put("created_at", formatInstant(record.getCreatedAt()));
        payload.put("created_at_epoch", record.getCreatedAt() != null ? record.getCreatedAt().getEpochSecond() : null);
        payload.put("ttl_days", record.getTtlDays());
        return payload;
    }

    private String formatInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private Map<String, Object> buildFilter(VectorSearchQuery query) {
        List<Map<String, Object>> must = new ArrayList<>();

        if (StringUtils.hasText(query.getUserId())) {
            must.add(matchFilter("user_id", query.getUserId()));
        }

        if (StringUtils.hasText(query.getSessionId())) {
            must.add(matchFilter("session_id", query.getSessionId()));
        }

        if (query.getFilters() != null) {
            for (Map.Entry<String, Object> entry : query.getFilters().entrySet()) {
                must.add(matchFilter(entry.getKey(), entry.getValue()));
            }
        }

        if (must.isEmpty()) {
            return null;
        }

        Map<String, Object> filter = new HashMap<>();
        filter.put("must", must);
        return filter;
    }

    private Map<String, Object> matchFilter(String key, Object value) {
        Map<String, Object> match = new HashMap<>();
        match.put("value", value);

        Map<String, Object> condition = new HashMap<>();
        condition.put("key", key);
        condition.put("match", match);
        return condition;
    }

    private List<MemorySearchResult> parseSearchResults(String responseJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(responseJson, new TypeReference<>() {});
            Object resultValue = root.get("result");
            if (!(resultValue instanceof List<?> results)) {
                return List.of();
            }

            List<MemorySearchResult> output = new ArrayList<>();
            for (Object resultItem : results) {
                if (!(resultItem instanceof Map<?, ?> resultMap)) {
                    continue;
                }
                String memoryId = String.valueOf(resultMap.get("id"));
                Double score = resultMap.get("score") instanceof Number number ? number.doubleValue() : null;
                Map<String, Object> payload = castPayload(resultMap.get("payload"));
                String text = payload != null ? String.valueOf(payload.get("text")) : null;
                Map<String, Object> metadata = payload != null ? castPayload(payload.get("metadata")) : null;
                Instant createdAt = parseInstant(payload != null ? payload.get("created_at") : null);
                output.add(new MemorySearchResult(memoryId, score, text, metadata, createdAt));
            }
            return output;
        } catch (Exception ex) {
            log.error("Failed to parse qdrant search response", ex);
            throw new IllegalStateException("Qdrant search response parse failed", ex);
        }
    }

    @Override
    public List<MemoryArchiveItem> scrollForArchive(Instant before, int limit) {
        if (before == null) {
            return List.of();
        }

        Map<String, Object> range = new HashMap<>();
        range.put("lt", before.getEpochSecond());

        Map<String, Object> condition = new HashMap<>();
        condition.put("key", "created_at_epoch");
        condition.put("range", range);

        Map<String, Object> filter = new HashMap<>();
        filter.put("must", List.of(condition));

        Map<String, Object> body = new HashMap<>();
        body.put("filter", filter);
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vectors", false);

        String responseJson = webClient.post()
                .uri("/collections/{collection}/points/scroll", properties.getCollectionName())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (responseJson == null || responseJson.isBlank()) {
            return List.of();
        }

        return parseArchiveCandidates(responseJson);
    }

    private List<MemoryArchiveItem> parseArchiveCandidates(String responseJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(responseJson, new TypeReference<>() {});
            Object resultValue = root.get("result");
            if (!(resultValue instanceof Map<?, ?> resultMap)) {
                return List.of();
            }
            Object pointsValue = resultMap.get("points");
            if (!(pointsValue instanceof List<?> points)) {
                return List.of();
            }

            List<MemoryArchiveItem> items = new ArrayList<>();
            for (Object pointItem : points) {
                if (!(pointItem instanceof Map<?, ?> pointMap)) {
                    continue;
                }
                String memoryId = String.valueOf(pointMap.get("id"));
                Map<String, Object> payload = castPayload(pointMap.get("payload"));
                if (payload == null) {
                    continue;
                }
                String userId = payload.get("user_id") != null ? String.valueOf(payload.get("user_id")) : null;
                String sessionId = payload.get("session_id") != null ? String.valueOf(payload.get("session_id")) : null;
                String text = payload.get("text") != null ? String.valueOf(payload.get("text")) : null;
                Map<String, Object> metadata = castPayload(payload.get("metadata"));
                Instant createdAt = parseInstant(payload.get("created_at"));
                items.add(new MemoryArchiveItem(memoryId, userId, sessionId, text, metadata, createdAt));
            }

            return items;
        } catch (Exception ex) {
            log.error("Failed to parse qdrant scroll response", ex);
            return List.of();
        }
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castPayload(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }
}
