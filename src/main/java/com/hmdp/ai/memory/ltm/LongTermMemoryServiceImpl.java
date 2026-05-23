package com.hmdp.ai.memory.ltm;

import com.hmdp.ai.memory.ltm.dto.IngestRequest;
import com.hmdp.ai.memory.ltm.dto.MemoryRecord;
import com.hmdp.ai.memory.ltm.dto.MemorySearchResult;
import com.hmdp.ai.memory.ltm.dto.SearchRequest;
import com.hmdp.ai.memory.ltm.dto.VectorSearchQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class LongTermMemoryServiceImpl implements LongTermMemoryService {

    private static final double MIN_FINAL_SCORE = 0.7;  // 最终得分的最低阈值，低于该值的结果将被过滤掉
    private static final double WEIGHT_RELEVANCE = 0.5; // 相关性得分的权重
    private static final double WEIGHT_RECENCY = 0.25;  // 新鲜度得分的权重
    private static final double WEIGHT_IMPORTANCE = 0.25;  // 重要性得分的权重
    private static final Pattern FACT_KEY_PATTERN = Pattern.compile("^\\s*([^:=：]{1,24})\\s*(?:=|:|：|是)\\s*.+$");

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public LongTermMemoryServiceImpl(EmbeddingService embeddingService, VectorStore vectorStore) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    @Override
    public List<String> ingest(IngestRequest request) {
        validateUser(request.getUserId());
        if (!StringUtils.hasText(request.getText())) {
            return List.of();
        }

        List<List<Float>> embeddings = embeddingService.embed(List.of(request.getText()));
        if (embeddings.isEmpty()) {
            return List.of();
        }

        String memoryId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> metadata = new HashMap<>();
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        if (StringUtils.hasText(request.getType())) {
            metadata.put("type", request.getType());
        }
        ensureFactKeys(metadata, request.getText());
        ensureImportanceScore(metadata);

        MemoryRecord record = new MemoryRecord(
                memoryId,
                request.getUserId(),
                request.getSessionId(),
                request.getText(),
                embeddings.get(0),
                metadata,
                Instant.now(),
                null
        );

        return vectorStore.upsert(List.of(record));
    }

    @Override
    public List<MemorySearchResult> search(SearchRequest request) {
        validateUser(request.getUserId());
        if (!StringUtils.hasText(request.getText())) {
            return List.of();
        }

        List<List<Float>> embeddings = embeddingService.embed(List.of(request.getText()));
        if (embeddings.isEmpty()) {
            return List.of();
        }

        VectorSearchQuery query = new VectorSearchQuery(
                request.getUserId(),
                request.getSessionId(),
                embeddings.get(0),
                request.getTopK() != null ? request.getTopK() : 5,
                request.getFilters()
        );

        List<MemorySearchResult> rawResults = vectorStore.query(query);
        if (rawResults == null || rawResults.isEmpty()) {
            return List.of();
        }

        List<MemorySearchResult> scored = new ArrayList<>();
        for (MemorySearchResult result : rawResults) {
            double finalScore = computeFinalScore(result);
            if (finalScore < MIN_FINAL_SCORE) {
                continue;
            }
            result.setScore(finalScore);
            scored.add(result);
        }

        List<MemorySearchResult> deduped = deduplicate(scored);
        List<MemorySearchResult> resolved = resolveConflicts(deduped);
        resolved.sort(Comparator.comparing(MemorySearchResult::getScore).reversed());
        return resolved;
    }

    private double computeFinalScore(MemorySearchResult result) {
        double relevance = result.getScore() != null ? result.getScore() : 0.0;
        double recency = computeRecencyScore(result.getCreatedAt());
        double importance = computeImportanceScore(result.getMetadata());

        return (WEIGHT_RELEVANCE * relevance)
                + (WEIGHT_RECENCY * recency)
                + (WEIGHT_IMPORTANCE * importance);
    }

    private double computeRecencyScore(Instant createdAt) {
        if (createdAt == null) {
            return 0.5;
        }
        long days = Math.max(0, Duration.between(createdAt, Instant.now()).toDays());
        double lambda = 0.001;
        return Math.exp(-lambda * days);
    }

    private double computeImportanceScore(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return 0.5;
        }
        Object value = metadata.get("importance_score");
        if (!(value instanceof Number number)) {
            return 0.5;
        }
        double score = number.doubleValue();
        if (score > 1.0) {
            score = score / 10.0;
        }
        return Math.min(1.0, Math.max(0.0, score));
    }

    private List<MemorySearchResult> deduplicate(List<MemorySearchResult> results) {
        Map<String, MemorySearchResult> byText = new LinkedHashMap<>();
        for (MemorySearchResult result : results) {
            String text = result.getText() == null ? "" : result.getText().trim().toLowerCase();
            MemorySearchResult existing = byText.get(text);
            if (existing == null || compareScore(result, existing) > 0) {
                byText.put(text, result);
            }
        }
        return new ArrayList<>(byText.values());
    }

    private List<MemorySearchResult> resolveConflicts(List<MemorySearchResult> results) {
        Map<String, MemorySearchResult> resolved = new LinkedHashMap<>();
        for (MemorySearchResult result : results) {
            String conflictKey = readMetadataKey(result.getMetadata(), "conflict_key");
            if (conflictKey == null) {
                conflictKey = readMetadataKey(result.getMetadata(), "fact_key");
            }

            if (conflictKey == null) {
                resolved.put(result.getMemoryId(), result);
                continue;
            }

            MemorySearchResult existing = resolved.get(conflictKey);
            if (existing == null || compareRecency(result, existing) > 0) {
                resolved.put(conflictKey, result);
            }
        }

        return new ArrayList<>(resolved.values());
    }

    private String readMetadataKey(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private int compareScore(MemorySearchResult left, MemorySearchResult right) {
        double leftScore = left.getScore() != null ? left.getScore() : 0.0;
        double rightScore = right.getScore() != null ? right.getScore() : 0.0;
        return Double.compare(leftScore, rightScore);
    }

    private int compareRecency(MemorySearchResult left, MemorySearchResult right) {
        Instant leftTime = left.getCreatedAt();
        Instant rightTime = right.getCreatedAt();
        if (leftTime == null && rightTime == null) {
            return compareScore(left, right);
        }
        if (leftTime == null) {
            return -1;
        }
        if (rightTime == null) {
            return 1;
        }
        return leftTime.compareTo(rightTime);
    }

    private void validateUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private void ensureFactKeys(Map<String, Object> metadata, String text) {
        if (metadata == null || !StringUtils.hasText(text)) {
            return;
        }
        if (metadata.containsKey("conflict_key") || metadata.containsKey("fact_key")) {
            return;
        }

        String factKey = extractFactKey(text);
        if (factKey != null) {
            metadata.put("fact_key", factKey);
            metadata.put("conflict_key", factKey);
        }
    }

    private String extractFactKey(String text) {
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        Matcher matcher = FACT_KEY_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            return matcher.group(1).trim().toLowerCase();
        }
        return null;
    }

    private void ensureImportanceScore(Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        if (metadata.containsKey("importance_score")) {
            return;
        }
        metadata.put("importance_score", 0.5);
    }
}
