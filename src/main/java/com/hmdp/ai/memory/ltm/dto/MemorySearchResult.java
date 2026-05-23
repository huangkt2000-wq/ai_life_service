package com.hmdp.ai.memory.ltm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemorySearchResult {

    private String memoryId;
    private Double score;
    private String text;
    private Map<String, Object> metadata;
    private Instant createdAt;
}
