package com.hmdp.ai.memory.ltm.controller;

import com.hmdp.ai.memory.ltm.LongTermMemoryService;
import com.hmdp.ai.memory.ltm.VectorStore;
import com.hmdp.ai.memory.ltm.dto.ForgetRequest;
import com.hmdp.ai.memory.ltm.dto.IngestRequest;
import com.hmdp.ai.memory.ltm.dto.MemorySearchResult;
import com.hmdp.ai.memory.ltm.dto.SearchRequest;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final LongTermMemoryService longTermMemoryService;
    private final VectorStore vectorStore;

    public MemoryController(LongTermMemoryService longTermMemoryService, VectorStore vectorStore) {
        this.longTermMemoryService = longTermMemoryService;
        this.vectorStore = vectorStore;
    }

    @PostMapping("/ingest")
    public Result ingest(@RequestBody IngestRequest request) {
        List<String> ids = longTermMemoryService.ingest(request);
        Map<String, Object> response = new HashMap<>();
        response.put("memory_ids", ids);
        return Result.ok(response);
    }

    @PostMapping("/query")
    public Result query(@RequestBody SearchRequest request) {
        List<MemorySearchResult> results = longTermMemoryService.search(request);
        return Result.ok(results);
    }

    @DeleteMapping("/forget")
    public Result forget(@RequestBody ForgetRequest request) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return Result.fail("user not logged in");
        }

        boolean deleted;
        if (CollectionUtils.isEmpty(request.getMemoryIds())) {
            deleted = vectorStore.deleteByUser(String.valueOf(userId));
        } else {
            deleted = vectorStore.delete(request.getMemoryIds());
        }
        return deleted ? Result.ok() : Result.fail("delete failed");
    }
}
