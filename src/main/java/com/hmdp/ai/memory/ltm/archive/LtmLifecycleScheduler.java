package com.hmdp.ai.memory.ltm.archive;

import com.hmdp.ai.memory.ltm.VectorStore;
import com.hmdp.ai.memory.ltm.config.LtmLifecycleProperties;
import com.hmdp.ai.memory.ltm.dto.MemoryArchiveItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
public class LtmLifecycleScheduler {

    private final VectorStore vectorStore;
    private final MinioArchiver minioArchiver;
    private final LtmLifecycleProperties properties;

    public LtmLifecycleScheduler(VectorStore vectorStore, MinioArchiver minioArchiver, LtmLifecycleProperties properties) {
        this.vectorStore = vectorStore;
        this.minioArchiver = minioArchiver;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${memory.long-term.lifecycle.scan-interval-ms:3600000}")
    public void archiveOldMemories() {
        Instant cutoff = Instant.now().minus(properties.getArchiveAfterDays(), ChronoUnit.DAYS);
        int batchSize = Math.max(1, properties.getScanBatchSize());

        log.info("LTM archive scan started: cutoff={}, batchSize={}", cutoff, batchSize);
        List<MemoryArchiveItem> items = vectorStore.scrollForArchive(cutoff, batchSize);
        if (items == null || items.isEmpty()) {
            log.info("LTM archive scan finished: no candidates");
            return;
        }

        int archived = 0;
        int failed = 0;
        for (MemoryArchiveItem item : items) {
            if (item == null || item.getMemoryId() == null || item.getMemoryId().isBlank()) {
                failed++;
                continue;
            }
            try {
                String objectName = minioArchiver.archive(item, properties.getArchivePath());
                boolean deleted = vectorStore.delete(List.of(item.getMemoryId()));
                if (deleted) {
                    archived++;
                    log.debug("LTM archived: memoryId={}, object={}", item.getMemoryId(), objectName);
                } else {
                    failed++;
                    log.warn("LTM archive delete failed: memoryId={}", item.getMemoryId());
                }
            } catch (Exception ex) {
                failed++;
                log.warn("LTM archive failed: memoryId={}", item.getMemoryId(), ex);
            }
        }

        log.info("LTM archive scan finished: total={}, archived={}, failed={}", items.size(), archived, failed);
    }
}
