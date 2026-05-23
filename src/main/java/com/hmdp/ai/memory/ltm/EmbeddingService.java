package com.hmdp.ai.memory.ltm;

import java.util.List;

public interface EmbeddingService {

    List<List<Float>> embed(List<String> texts);
}
