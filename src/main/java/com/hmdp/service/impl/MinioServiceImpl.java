package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.MinioProperties;
import com.hmdp.service.IMinioService;
import io.minio.*;
import io.minio.errors.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * MinIO 文件存储服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioServiceImpl implements IMinioService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /**
     * 启动时检查并创建存储桶
     */
    @PostConstruct
    public void initBucket() {
        try {
            String bucketName = minioProperties.getBucketName();
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucketName)
                    .build());

            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucketName)
                        .build());
                log.info("创建 MinIO 存储桶成功: {}", bucketName);
            } else {
                log.info("MinIO 存储桶已存在: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("初始化 MinIO 存储桶失败", e);
        }
    }

    @Override
    public String uploadFile(MultipartFile file, String folder) {
        try {
            // 获取原始文件名
            String originalFilename = file.getOriginalFilename();
            if (StrUtil.isBlank(originalFilename)) {
                originalFilename = "unknown";
            }

            // 获取文件后缀
            String suffix = StrUtil.subAfter(originalFilename, ".", true);
            if (StrUtil.isBlank(suffix)) {
                suffix = "jpg";
            }

            // 生成唯一文件名，保持原有目录结构
            String uuid = UUID.randomUUID().toString();
            int hash = uuid.hashCode();
            int d1 = hash & 0xF;
            int d2 = (hash >> 4) & 0xF;

            // 对象名称: folder/d1/d2/uuid.suffix
            String objectName = StrUtil.format("{}/{}/{}.{}", folder, d1, d2, uuid, suffix);

            // 上传文件
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());

            log.info("文件上传成功: {}", objectName);

            // 返回文件 URL
            return getFileUrl(objectName);

        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteFile(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectName)
                    .build());

            log.info("文件删除成功: {}", objectName);

        } catch (Exception e) {
            log.error("文件删除失败: {}", objectName, e);
            throw new RuntimeException("文件删除失败: " + e.getMessage());
        }
    }

    @Override
    public String getFileUrl(String objectName) {
        // 如果配置了 baseUrl，使用 baseUrl
        if (StrUtil.isNotBlank(minioProperties.getBaseUrl())) {
            return StrUtil.format("{}/{}/{}",
                    minioProperties.getBaseUrl(),
                    minioProperties.getBucketName(),
                    objectName);
        }

        // 否则使用 endpoint
        String endpoint = minioProperties.getEndpoint();
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return StrUtil.format("{}/{}/{}",
                    endpoint,
                    minioProperties.getBucketName(),
                    objectName);
        }

        // 默认 http
        return StrUtil.format("http://{}/{}/{}",
                endpoint,
                minioProperties.getBucketName(),
                objectName);
    }
}