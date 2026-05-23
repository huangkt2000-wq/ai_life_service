package com.hmdp.ai.memory.ltm;

import com.hmdp.ai.memory.ltm.dto.IngestRequest;
import com.hmdp.ai.memory.ltm.dto.MemorySearchResult;
import com.hmdp.ai.memory.ltm.dto.SearchRequest;

import java.util.List;

public interface LongTermMemoryService {

    List<String> ingest(IngestRequest request);

    List<MemorySearchResult> search(SearchRequest request);
}
