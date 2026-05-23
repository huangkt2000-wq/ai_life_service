package com.hmdp.ai.memory.ltm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {

    private String userId;
    private String sessionId;
    private String text;
    private Integer topK;
    private Map<String, Object> filters;
}
