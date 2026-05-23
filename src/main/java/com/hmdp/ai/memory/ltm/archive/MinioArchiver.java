package com.hmdp.ai.memory.ltm.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.memory.ltm.dto.MemoryArchiveItem;
import com.hmdp.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

@Slf4j
@Service
public class MinioArchiver {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final ObjectMapper objectMapper;

    public MinioArchiver(MinioClient minioClient, MinioProperties minioProperties, ObjectMapper objectMapper) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.objectMapper = objectMapper;
    }

    public String archive(MemoryArchiveItem item, String pathTemplate) {
        if (item == null) {
            throw new IllegalArgumentException("archive item is null");
        }
        if (item.getMemoryId() == null || item.getMemoryId().isBlank()) {
            throw new IllegalArgumentException("memoryId is required for archive");
        }
        try {
            String objectName = buildObjectName(item, pathTemplate);
            byte[] payload = objectMapper.writeValueAsBytes(item);

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectName)
                    .stream(new ByteArrayInputStream(payload), payload.length, -1)
                    .contentType("application/json")
                    .build());
            log.debug("LTM archived to MinIO: memoryId={}, object={}, size={} bytes", item.getMemoryId(), objectName, payload.length);
            return objectName;
        } catch (Exception ex) {
            log.error("Failed to archive memory: {}", item.getMemoryId(), ex);
            throw new IllegalStateException("MinIO archive failed", ex);
        }
    }

    private String buildObjectName(MemoryArchiveItem item, String template) {
        String objectName = template;
        objectName = objectName.replace("{user_id}", safe(item.getUserId()));
        objectName = objectName.replace("{memory_id}", safe(item.getMemoryId()));
        return objectName.startsWith("/") ? objectName.substring(1) : objectName;
    }

    private String safe(String value) {
        return value == null ? "unknown" : value;
    }
}
