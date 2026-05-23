package com.hmdp.ai.memory.ltm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryArchiveItem {

    private String memoryId;
    private String userId;
    private String sessionId;
    private String text;
    private Map<String, Object> metadata;
    private Instant createdAt;
}
