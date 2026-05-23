package com.hmdp.ai.memory.ltm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForgetRequest {

    private String userId;
    private List<String> memoryIds;
}
