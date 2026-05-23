package com.hmdp.ai.memory.ltm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VectorSearchQuery {

    private String userId;
    private String sessionId;
    private List<Float> vector;
    private Integer topK;
    private Map<String, Object> filters;
}
