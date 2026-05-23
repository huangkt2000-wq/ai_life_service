package com.hmdp.ai.memory.ltm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "memory.long-term.qdrant")
public class QdrantProperties {

    private String endpoint;
    private String apiKey;
    private String collectionName;
    private Integer vectorSize = 1536;
    private String distance = "Cosine";
    private Integer timeoutMs = 5000;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public Integer getVectorSize() {
        return vectorSize;
    }

    public void setVectorSize(Integer vectorSize) {
        this.vectorSize = vectorSize;
    }

    public String getDistance() {
        return distance;
    }

    public void setDistance(String distance) {
        this.distance = distance;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
