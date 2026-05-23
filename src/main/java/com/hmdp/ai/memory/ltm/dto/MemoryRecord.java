package com.hmdp.ai.memory.ltm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRecord {

    private String memoryId;
    private String userId;
    private String sessionId;
    private String text;
    private List<Float> vector;
    private Map<String, Object> metadata;
    private Instant createdAt;
    private Integer ttlDays;
}
