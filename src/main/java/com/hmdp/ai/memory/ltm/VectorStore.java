package com.hmdp.ai.memory.ltm;

import com.hmdp.ai.memory.ltm.dto.MemoryRecord;
import com.hmdp.ai.memory.ltm.dto.MemoryArchiveItem;
import com.hmdp.ai.memory.ltm.dto.MemorySearchResult;
import com.hmdp.ai.memory.ltm.dto.VectorSearchQuery;

import java.time.Instant;
import java.util.List;

public interface VectorStore {

    List<String> upsert(List<MemoryRecord> records);

    List<MemorySearchResult> query(VectorSearchQuery query);

    boolean delete(List<String> memoryIds);

    boolean deleteByUser(String userId);

    List<MemoryArchiveItem> scrollForArchive(Instant before, int limit);
}
