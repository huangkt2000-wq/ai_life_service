package com.hmdp.ai.memory.ltm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.memory.ltm.config.EmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DashscopeEmbeddingService implements EmbeddingService {

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public DashscopeEmbeddingService(
            EmbeddingProperties properties,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
        if (CollectionUtils.isEmpty(texts)) {
            return List.of();
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", properties.getModel());
        requestBody.put("input", texts);

        String responseJson = webClient.post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (responseJson == null || responseJson.isBlank()) {
            return List.of();
        }

        try {
            Map<String, Object> root = objectMapper.readValue(responseJson, new TypeReference<>() {});
            Object dataValue = root.get("data");
            if (!(dataValue instanceof List<?> dataList)) {
                return List.of();
            }

            List<List<Float>> embeddings = new ArrayList<>();
            for (Object item : dataList) {
                if (!(item instanceof Map<?, ?> itemMap)) {
                    continue;
                }
                Object embeddingValue = itemMap.get("embedding");
                if (!(embeddingValue instanceof List<?> rawEmbedding)) {
                    continue;
                }
                List<Float> vector = new ArrayList<>(rawEmbedding.size());
                for (Object value : rawEmbedding) {
                    if (value instanceof Number number) {
                        vector.add(number.floatValue());
                    }
                }
                embeddings.add(vector);
            }

            return embeddings;
        } catch (Exception ex) {
            log.error("Failed to parse embedding response", ex);
            throw new IllegalStateException("Embedding response parse failed", ex);
        }
    }
}
