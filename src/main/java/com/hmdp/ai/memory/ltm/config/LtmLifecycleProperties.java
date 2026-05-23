package com.hmdp.ai.memory.ltm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "memory.long-term.lifecycle")
public class LtmLifecycleProperties {

    private int archiveAfterDays = 365;
    private int scanBatchSize = 200;
    private long scanIntervalMs = 3600000;
    private String archivePath = "/memory-archive/{user_id}/{memory_id}.json";
}
